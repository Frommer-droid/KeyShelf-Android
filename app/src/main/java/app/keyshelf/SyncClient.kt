package app.keyshelf

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*

data class SyncResult(val action: String, val hash: String, val bytes: ByteArray? = null, val modifiedAt: Long = 0, val expectedLocal: String? = null, val name: String = "vault.kdbx")

interface SyncTransport {
    fun startListening(onEvent: () -> Unit): AutoCloseable? = null
    fun pair(invitation: String)
    fun exchange(local: ByteArray?, outgoing: ByteArray? = null): SyncResult
    fun offer(local: ByteArray, expected: String, conflict: Boolean): SyncResult
    fun rejectRemote(local: ByteArray, reviewedHash: String): SyncResult
    fun verifyRemote(local: ByteArray, reviewedHash: String)
    fun acknowledge(hash: String)
}

class SyncClient(private val context: Context) : SyncTransport {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias("keyshelf-pair")) return store.getKey("keyshelf-pair", null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("keyshelf-pair", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): JSONObject {
        val encoded = context.getSharedPreferences("sync", 0).getString("pair", null) ?: error("Нет сопряжения")
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    private fun save(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray())
        check(context.getSharedPreferences("sync", 0).edit().putString("pair", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }
    override fun pair(invitation: String) {
        val invite = JSONObject(invitation)
        require(invite.getInt("version") == 1 && invite.getLong("expires") > System.currentTimeMillis() / 1000) { "Приглашение истекло" }
        validateHost(invite.getString("host"))
        require(invite.getString("pin").matches(Regex("[0-9a-f]{64}")))
        val result = request(invite, "/pair", JSONObject().put("invite", invite.getString("invite")), false)
        invite.remove("invite"); invite.remove("expires")
        invite.put("token", result.getString("token")); invite.put("base", JSONObject.NULL)
        save(invite)
    }
    override fun exchange(local: ByteArray?, outgoing: ByteArray?): SyncResult {
        val pair = read()
        val digest = local?.let(VaultCodec::hash).orEmpty()
        val response = request(pair, "/sync", JSONObject().put("protocol", 3).put("base", pair.opt("base"))
            .put("hash", digest).put("outgoing", outgoing?.let(VaultCodec::hash).orEmpty()).put("size", local?.size ?: 0))
        require(response.optInt("protocol") == 3) { "Обновите KeyShelf на компьютере: нужен обмен с подтверждением" }
        val action = response.getString("action")
        val hash = response.getString("hash")
        require(action in listOf("idle", "same", "ack", "pull", "push", "restore")) { "Неизвестный ответ синхронизации" }
        if (action == "same") {
            require(hash == digest)
            acknowledge(hash)
        }
        if (action == "push") {
            require(outgoing != null) { "Нет сохранённого пакета" }
            offer(outgoing, hash, false)
        }
        val bytes = if (response.has("blob")) Base64.decode(response.getString("blob"), Base64.NO_WRAP) else null
        if (action == "restore") require(response.getString("expectedLocal") == digest) { "База телефона изменилась после ответа компьютера" }
        return SyncResult(action, hash, bytes, response.optLong("modified"),
            if (response.has("expectedLocal")) response.getString("expectedLocal") else null, response.optString("name", "vault.kdbx"))
    }
    override fun offer(local: ByteArray, expected: String, conflict: Boolean): SyncResult {
        val result = request(read(), "/upload", JSONObject().put("protocol", 3).put("expected", expected)
            .put("decision", "offer").put("modified", ManualSync(context.applicationContext as android.app.Application).modifiedAt).put("blob", Base64.encodeToString(local, Base64.NO_WRAP)))
        require(result.optInt("protocol") == 3 && result.getString("action") == "pending"
            && result.getString("hash") == VaultCodec.hash(local)) { "Компьютер не сохранил запрос на подтверждение" }
        return SyncResult("pending", result.getString("hash"))
    }
    override fun rejectRemote(local: ByteArray, reviewedHash: String): SyncResult {
        val digest = VaultCodec.hash(local)
        val result = request(read(), "/upload", JSONObject().put("protocol", 3).put("expected", reviewedHash)
            .put("decision", "restore_desktop").put("blob", Base64.encodeToString(local, Base64.NO_WRAP)))
        require(result.optInt("protocol") == 3 && result.getString("action") == "same" && result.getString("hash") == digest) {
            "Компьютер не подтвердил восстановление базы"
        }
        acknowledge(digest)
        return SyncResult("same", digest)
    }
    // Before applying a reviewed proposal, check that the sender still has that exact version.
    override fun verifyRemote(local: ByteArray, reviewedHash: String) {
        val pair = read()
        val response = request(pair, "/sync", JSONObject().put("protocol", 3).put("base", pair.opt("base"))
            .put("hash", VaultCodec.hash(local)).put("outgoing", "").put("size", local.size))
        require(response.optInt("protocol") == 3 && response.getString("hash") == reviewedHash) {
            "База компьютера изменилась. Проверьте новые изменения перед принятием."
        }
    }
    override fun acknowledge(hash: String) { val pair = read(); pair.put("base", hash); save(pair) }
    override fun startListening(onEvent: () -> Unit): AutoCloseable {
        class Subscription : AutoCloseable {
            @Volatile var closed = false
            @Volatile var connection: HttpsURLConnection? = null
            override fun close() { closed = true; connection?.disconnect() }
        }
        val subscription = Subscription()
        Thread({
            while (!subscription.closed) {
                try {
                    val connection = open(read(), "/events")
                    subscription.connection = connection
                    connection.readTimeout = 0
                    val body = "{\"protocol\":3}".toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                    require(connection.responseCode == 200)
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (!subscription.closed) {
                            val line = reader.readLine() ?: break
                            require(line.length <= 256)
                            if (JSONObject(line).optString("event") == "changed") onEvent()
                        }
                    }
                } catch (_: Exception) {
                    // Reconnect the passive notification channel, without publishing or polling a vault.
                } finally {
                    subscription.connection?.disconnect(); subscription.connection = null
                }
                if (!subscription.closed) Thread.sleep(3000)
            }
        }, "KeyShelf saved-package events").apply { isDaemon = true; start() }
        return subscription
    }
    private fun wifi(host: String): Network {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val remote = java.net.InetAddress.getByName(host).address
        fun number(bytes: ByteArray): Long = bytes.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
        return cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(network)?.linkAddresses?.any { local ->
                    if (local.address !is java.net.Inet4Address || local.prefixLength !in 1..32) false
                    else {
                        val mask = (0xffffffffL shl (32 - local.prefixLength)) and 0xffffffffL
                        number(local.address.address) and mask == number(remote) and mask
                    }
                } == true
        } ?: throw IllegalArgumentException("Телефон и компьютер должны быть в одной Wi-Fi подсети")
    }
    private fun open(pair: JSONObject, path: String, auth: Boolean = true): HttpsURLConnection {
        val host = pair.getString("host"); validateHost(host)
        val port = pair.getInt("port"); require(port in 1024..65535)
        val pin = pair.getString("pin")
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) { error("Unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                if (chain.isEmpty() || VaultCodec.hash(chain.first().encoded) != pin)
                    throw java.security.cert.CertificateException("Другой сертификат компьютера")
                chain.first().checkValidity()
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        wifi(host) // Confirm a physical Wi-Fi interface in the destination's subnet.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val selected = manager.activeNetwork ?: throw IllegalArgumentException("Нет активного Wi-Fi соединения")
        val capabilities = manager.getNetworkCapabilities(selected)
        require(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Для обмена выберите Wi-Fi как активное подключение"
        }
        // A VPN may prohibit binding to its underlying Wi-Fi network (EPERM).
        // Respect the selected system route instead of attempting to bypass it.
        val connection = selected.openConnection(URL("https://$host:$port$path")) as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        // Identity is the exact QR-pinned certificate, not a DNS hostname.
        connection.hostnameVerifier = HostnameVerifier { _, session ->
            runCatching { VaultCodec.hash(session.peerCertificates.first().encoded) == pin }.getOrDefault(false)
        }
        connection.connectTimeout = 5000; connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        if (auth) connection.setRequestProperty("Authorization", "Bearer " + pair.getString("token"))
        return connection
    }
    private fun request(pair: JSONObject, path: String, body: JSONObject, auth: Boolean = true): JSONObject {
        val connection = open(pair, path, auth)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        try {
            connection.outputStream.use { it.write(bytes) }
            require(connection.responseCode == 200) {
                when (connection.responseCode) { 426 -> "Обновите обе версии KeyShelf для обмена с подтверждением"
                    409 -> "База на компьютере изменилась. Повторите обмен."
                    401 -> if (path == "/pair") "QR устарел или уже использован. Нажмите «Обновить QR» на компьютере и сканируйте снова."
                        else "Сопряжение отозвано. Свяжите устройства заново."
                    503 -> "Откройте хранилище на компьютере и повторите обмен."
                    else -> "Компьютер не принял запрос" }
            }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break
                    require(output.size() + count <= 46 * 1024 * 1024); output.write(buffer, 0, count) }
            }
            return JSONObject(output.toString("UTF-8"))
        } finally { connection.disconnect() }
    }
    companion object {
        fun isPaired(context: Context) = context.getSharedPreferences("sync", 0).contains("pair")
        fun clear(context: Context) { context.getSharedPreferences("sync", 0).edit().clear().commit() }
        fun validateHost(host: String) {
            val parts = host.split('.').map { it.toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null && it in 0..255 }) { "Нужен локальный IPv4 компьютера" }
            val a = parts[0]!!; val b = parts[1]!!
            require(a == 10 || a == 192 && b == 168 || a == 172 && b in 16..31) { "Синхронизация доступна только в локальной сети" }
        }
    }
}
