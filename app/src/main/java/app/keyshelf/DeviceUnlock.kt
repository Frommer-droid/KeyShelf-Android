package app.keyshelf

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface UnlockCipher {
    fun encrypt(bytes: ByteArray): ByteArray
    fun decrypt(bytes: ByteArray): ByteArray
}

class AndroidUnlockCipher : UnlockCipher {
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias("keyshelf-unlock")) return store.getKey("keyshelf-unlock", null) as SecretKey
        check(create) { "Ключ автоматического открытия недоступен" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("keyshelf-unlock", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        return cipher.iv + cipher.doFinal(bytes)
    }
    override fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}

class DeviceUnlock(context: Context, private val cipher: UnlockCipher = AndroidUnlockCipher()) {
    private val preferences = context.getSharedPreferences("vault_access", 0)
    private fun read(): JSONObject {
        val encoded = preferences.getString("state", null) ?: return JSONObject()
        return JSONObject(String(cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8))
    }
    val required: Boolean get() = runCatching { read().optBoolean("required", true) }.getOrDefault(true)
    fun password(): String? = runCatching {
        val state = read()
        if (state.optBoolean("required", true) || !state.has("password")) null else state.getString("password")
    }.getOrNull()
    private fun save(state: JSONObject) {
        val encoded = Base64.encodeToString(cipher.encrypt(state.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString("state", encoded).commit()) { "Не удалось сохранить настройку открытия" }
    }
    fun setRequired(required: Boolean, password: String?) {
        val state = JSONObject().put("required", required)
        if (!required && password != null) state.put("password", password)
        save(state)
    }
    fun remember(password: String) {
        if (!required) setRequired(false, password)
    }
    fun forget() { save(JSONObject().put("required", false)) }
}
