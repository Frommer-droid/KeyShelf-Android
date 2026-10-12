package app.keyshelf

import android.app.AlertDialog
import android.os.Looper
import app.keemobile.kotpass.database.modifiers.removeEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = KeyShelfApp::class, shadows = [WindowsAtomicFileShadow::class])
class SyncReviewTest {
    private val pass = "synthetic-master-123"
    private class Computer : SyncTransport {
        var remote: ByteArray? = null
        var action = "pull"
        var expectedLocal: String? = null
        var acknowledged = ""
        var rejected = false
        var offers = 0
        var exchanges = 0
        var published: ByteArray? = null
        var onVerify: (() -> Unit)? = null
        override fun pair(invitation: String) {}
        override fun exchange(local: ByteArray?, outgoing: ByteArray?): SyncResult {
            exchanges++; published = outgoing
            val bytes = remote ?: local!!
            val effective = if (local != null && VaultCodec.hash(bytes) == VaultCodec.hash(local)) "idle" else action
            return SyncResult(effective, VaultCodec.hash(bytes), bytes, 1791790000000, expectedLocal)
        }
        override fun offer(local: ByteArray, expected: String, conflict: Boolean): SyncResult {
            offers++; return SyncResult("pending", VaultCodec.hash(local))
        }
        override fun verifyRemote(local: ByteArray, reviewedHash: String) {
            onVerify?.invoke()
            require(VaultCodec.hash(remote!!) == reviewedHash) { "База компьютера изменилась" }
        }
        override fun rejectRemote(local: ByteArray, reviewedHash: String): SyncResult {
            require(VaultCodec.hash(remote!!) == reviewedHash) { "База компьютера изменилась" }
            rejected = true; remote = local; acknowledged = VaultCodec.hash(local)
            return SyncResult("same", acknowledged)
        }
        override fun acknowledge(hash: String) { acknowledged = hash }
    }
    private fun wait(store: VaultStore, expectSuccess: Boolean = true) {
        val limit = System.currentTimeMillis() + 20000
        while (store.busy && System.currentTimeMillis() < limit) {
            Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(store.busy)
        if (expectSuccess) assertEquals("", store.error)
    }
    private fun populated(access: DeviceUnlock? = null): Pair<VaultStore, Computer> {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val computer = Computer()
        val store = VaultStore(app, access = access ?: DeviceUnlock(app), syncClient = { computer })
        app.store = store
        store.create(pass); wait(store)
        store.mutate { VaultCodec.saveRow(it, null, it.content.group.uuid, "Keep this record", "", "", "other", false,
            listOf(VaultField("", "Поле 1", "synthetic-secret", true))) }; wait(store)
        app.getSharedPreferences("sync", 0).edit().putString("pair", "synthetic-pair").commit()
        return store to computer
    }
    private fun deletion(store: VaultStore): ByteArray {
        val row = VaultCodec.rows(store.database!!).single()
        return VaultCodec.encode(store.database!!.removeEntry(row.entry.uuid))
    }
    @Test fun editsNeverSendOnTimerAndSaveFreezesOneBatchWithNewDraftStillDirty() {
        val (store, pc) = populated()
        assertTrue(store.hasUnsavedChanges)
        shadowOf(Looper.getMainLooper()).idleFor(45, TimeUnit.SECONDS)
        assertEquals(0, pc.exchanges)
        assertNull(store.manual.outgoing())
        pc.action = "idle"
        val published = store.file.readBytes()
        store.saveChanges(); wait(store)
        assertEquals(1, pc.exchanges)
        assertArrayEquals(published, pc.published)
        assertFalse(store.hasUnsavedChanges)
        store.mutate { VaultCodec.saveRow(it, null, it.content.group.uuid, "Next batch", "", "", "other", false,
            listOf(VaultField("", "Поле 1", "next", true))) }; wait(store)
        assertTrue(store.hasUnsavedChanges)
        assertArrayEquals(published, store.manual.outgoing())
        shadowOf(Looper.getMainLooper()).idleFor(45, TimeUnit.SECONDS)
        assertEquals(1, pc.exchanges)
        store.saveChanges(); wait(store)
        assertEquals(2, pc.exchanges)
        assertArrayEquals(store.file.readBytes(), pc.published)
    }
    @Test fun saveControlIsSquareFloppyIconAndDimsAfterPublishing() {
        val (store, pc) = populated(); pc.action = "idle"
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun find(view: android.view.View): android.widget.ImageButton? {
            if (view is android.widget.ImageButton && view.contentDescription?.toString() == "Сохранить и отправить изменения") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        fun button() = find(controller.get().window.decorView)!!
        assertEquals(button().layoutParams.width, button().layoutParams.height)
        assertEquals(Ui.dp(controller.get(), 48), button().layoutParams.width)
        assertTrue(button().isEnabled)
        assertEquals(1f, button().alpha)
        button().performClick(); wait(store)
        assertFalse(button().isEnabled)
        assertEquals(.4f, button().alpha)
        controller.pause().stop().destroy()
    }
    @Test fun privateUsbProvisionVerifiesBeforeReplacingAndRemovesLegacyCopies() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = object : UnlockCipher {
            override fun encrypt(bytes: ByteArray): ByteArray {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
                return cipher.iv + cipher.doFinal(bytes)
            }
            override fun decrypt(bytes: ByteArray): ByteArray {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            }
        }
        val access = DeviceUnlock(app, cipher)
        val (old, pc) = populated(access)
        old.setMasterPasswordRequired(false); wait(old)
        val seed = deletion(old)
        java.io.File(old.app.filesDir, "conflict-synthetic.kdbx").writeBytes(old.file.readBytes())
        java.io.File(old.app.filesDir, "vault.kdbx.bak").writeBytes(old.file.readBytes())
        java.io.File(old.app.filesDir, "provision.kdbx").writeBytes(seed)
        java.io.File(old.app.filesDir, "provision.json").writeText(org.json.JSONObject()
            .put("password", pass).put("hash", VaultCodec.hash(seed)).put("name", "Authoritative.kdbx").toString())
        old.app.applicationInfo.flags = old.app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        val imported = VaultStore(old.app, access = access, syncClient = { pc })
        imported.autoUnlock() // Activity onResume can run before the queued bootstrap.
        shadowOf(Looper.getMainLooper()).idle(); wait(imported)
        assertArrayEquals(seed, imported.file.readBytes())
        assertEquals("Authoritative.kdbx", imported.databaseName)
        assertFalse(imported.hasUnsavedChanges)
        assertFalse(java.io.File(old.app.filesDir, "conflict-synthetic.kdbx").exists())
        assertFalse(java.io.File(old.app.filesDir, "vault.kdbx.bak").exists())
        assertFalse(java.io.File(old.app.filesDir, "provision.json").exists())
        assertFalse(java.io.File(old.app.filesDir, "provision.kdbx").exists())
    }
    @Test fun incomingDeletionWaitsAndAcceptanceMakesExactMirrorWithBackup() {
        val (store, pc) = populated()
        val original = store.file.readBytes(); pc.remote = deletion(store)
        store.sync(); wait(store)
        assertArrayEquals(original, store.file.readBytes())
        assertEquals(1, VaultCodec.rows(store.database!!).size)
        assertNotNull(store.pendingSync)
        assertEquals(1791790000000, store.pendingSync!!.modifiedAt)
        assertEquals("", pc.acknowledged)
        store.acceptSync(); wait(store)
        assertArrayEquals(pc.remote, store.file.readBytes())
        assertTrue(VaultCodec.rows(store.database!!).isEmpty())
        assertArrayEquals(original, java.io.File(store.app.filesDir, "vault.previous.kdbx").readBytes())
        assertEquals(VaultCodec.hash(pc.remote!!), pc.acknowledged)
        assertNull(store.pendingSync)
    }
    @Test fun rejectionKeepsPhoneAndRestoresComputerDeletedRecord() {
        val (store, pc) = populated()
        val original = store.file.readBytes(); val deleted = deletion(store); pc.remote = deleted
        store.sync(); wait(store)
        store.rejectSync(); wait(store)
        assertTrue(pc.rejected)
        assertArrayEquals(original, pc.remote)
        assertArrayEquals(original, store.file.readBytes())
        assertEquals("Keep this record", VaultCodec.rows(store.database!!).single().title)
        assertTrue(store.syncReview.copies.listFiles()!!.any { it.readBytes().contentEquals(deleted) })
        assertNull(store.pendingSync)
    }
    @Test fun computerRejectionRestoresPhoneWithoutSecondQuestion() {
        val (store, pc) = populated()
        val original = store.file.readBytes()
        val row = VaultCodec.rows(store.database!!).single()
        store.mutate { it.removeEntry(row.entry.uuid) }; wait(store)
        pc.action = "restore"; pc.remote = original; pc.expectedLocal = VaultCodec.hash(store.file.readBytes())
        store.sync(); wait(store)
        assertArrayEquals(original, store.file.readBytes())
        assertEquals("Keep this record", VaultCodec.rows(store.database!!).single().title)
        assertNull(store.pendingSync)
        assertEquals(VaultCodec.hash(original), pc.acknowledged)
    }
    @Test fun staleSenderOrLocalChangesNeverApplyOldAnswer() {
        val (store, pc) = populated()
        val original = store.file.readBytes(); pc.remote = deletion(store)
        store.sync(); wait(store)
        val proposal = store.pendingSync!!
        pc.remote = original
        store.acceptSync(); wait(store, false)
        assertArrayEquals(original, store.file.readBytes())
        assertTrue(store.error.contains("изменилась"))
        store.rejectSync(); wait(store, false)
        assertFalse(pc.rejected)
        store.mutate { VaultCodec.saveRow(it, null, it.content.group.uuid, "New local", "", "", "other", false,
            listOf(VaultField("", "Поле 1", "value", true))) }; wait(store)
        val changed = store.file.readBytes()
        store.acceptSync(proposal); store.rejectSync(proposal)
        assertFalse(store.busy)
        assertArrayEquals(changed, store.file.readBytes())
    }
    @Test fun staleRestorationAndLockDuringConfirmationCannotOverwritePhone() {
        val (store, pc) = populated()
        val original = store.file.readBytes()
        pc.remote = deletion(store); pc.action = "restore"; pc.expectedLocal = "wrong hash"
        store.sync(); wait(store, false)
        assertArrayEquals(original, store.file.readBytes())
        pc.action = "pull"; store.sync(); wait(store)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        pc.onVerify = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        store.acceptSync()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        store.lock(); release.countDown(); wait(store)
        assertArrayEquals(original, store.file.readBytes())
        assertNull(store.database)
    }
    @Test fun pendingRestoresAfterUnlockAndDialogHasOnlyTwoAnswers() {
        val (store, pc) = populated()
        val original = store.file.readBytes(); pc.remote = deletion(store)
        store.sync(); wait(store)
        store.lock(); store.unlock(pass); wait(store)
        assertNotNull(store.pendingSync)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        wait(store)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals("Принять", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Отклонить", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals(android.view.View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle(); wait(store)
        assertArrayEquals(original, pc.remote)
        controller.pause().stop().destroy()
    }
}
