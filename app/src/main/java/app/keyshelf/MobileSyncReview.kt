package app.keyshelf

import android.app.Application
import android.util.AtomicFile
import java.io.File

// Only encrypted KDBX files and their hashes/timestamps are persisted.
data class SyncProposal(val localHash: String, val remoteHash: String, val modifiedAt: Long) {
    val key get() = "$localHash:$remoteHash"
}

class MobileSyncReview(private val app: Application) {
    private val prefs get() = app.getSharedPreferences("sync_review", 0)
    val copies = File(app.filesDir, "sync-copies")
    fun copy(bytes: ByteArray): File {
        copies.mkdirs()
        val target = File(copies, "${VaultCodec.hash(bytes)}.kdbx")
        if (!target.exists()) {
            val atomic = AtomicFile(target); val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
        return target
    }
    fun stage(local: ByteArray, remote: ByteArray, pass: String, result: SyncResult): SyncProposal {
        require(VaultCodec.hash(remote) == result.hash) { "Неверная контрольная сумма компьютера" }
        VaultCodec.decode(remote, pass)
        copy(remote)
        val proposal = SyncProposal(VaultCodec.hash(local), result.hash, result.modifiedAt)
        check(prefs.edit().putString("local", proposal.localHash).putString("remote", proposal.remoteHash)
            .putLong("modified", proposal.modifiedAt).commit())
        return proposal
    }
    fun load(pass: String, local: ByteArray): SyncProposal? {
        if (prefs.getString("local", null) != VaultCodec.hash(local)) return null
        val hash = prefs.getString("remote", null) ?: return null
        val file = File(copies, "$hash.kdbx")
        if (!file.exists()) return null
        return stage(local, file.readBytes(), pass, SyncResult("pull", hash, modifiedAt = prefs.getLong("modified", 0)))
    }
    fun remote(proposal: SyncProposal): ByteArray = File(copies, "${proposal.remoteHash}.kdbx").readBytes().also {
        require(VaultCodec.hash(it) == proposal.remoteHash) { "Ожидающая версия повреждена" }
    }
    fun clear() { check(prefs.edit().clear().commit()) }
}
