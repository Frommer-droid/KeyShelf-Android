package app.keyshelf

import android.app.Application
import android.util.AtomicFile
import java.io.File

class ManualSync(private val app: Application) {
    private val prefs get() = app.getSharedPreferences("manual_sync", 0)
    private val sent = File(app.filesDir, "manual-send.kdbx")
    fun initialize(hash: String) { if (!prefs.contains("saved")) check(prefs.edit().putString("saved", hash).commit()) }
    fun dirty(hash: String?) = hash != null && hash != prefs.getString("saved", hash)
    fun publish(bytes: ByteArray) {
        val hash = VaultCodec.hash(bytes)
        val atomic = AtomicFile(sent); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
        check(prefs.edit().putString("saved", hash).putString("outgoing", hash).putLong("modified", System.currentTimeMillis()).commit())
    }
    fun outgoing(): ByteArray? {
        val hash = prefs.getString("outgoing", null) ?: return null
        return sent.readBytes().also { require(VaultCodec.hash(it) == hash) { "Сохранённый пакет повреждён" } }
    }
    fun acknowledge(hash: String) {
        if (prefs.getString("outgoing", null) == hash) check(prefs.edit().remove("outgoing").commit())
    }
    fun received(hash: String) { check(prefs.edit().clear().putString("saved", hash).commit()) }
    val modifiedAt get() = prefs.getLong("modified", 0)
}
