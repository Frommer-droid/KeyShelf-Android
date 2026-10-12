package app.keyshelf

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = KeyShelfApp::class, shadows = [WindowsAtomicFileShadow::class])
class DeviceUnlockTest {
    // Real AES-GCM roundtrip; substitute only AndroidKeyStore's provider on JVM.
    private class TestCipher : UnlockCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(bytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(bytes)
        }
        override fun decrypt(bytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
    private fun wait(store: VaultStore) {
        shadowOf(Looper.getMainLooper()).idle()
        val end = System.currentTimeMillis() + 20000
        while (store.busy && System.currentTimeMillis() < end) {
            Thread.sleep(30); shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
        }
        assertFalse(store.busy); assertEquals("", store.error)
    }
    private fun hasPasswordInput(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view is EditText && view.hint?.toString() == "Мастер-пароль") return true
        return view is ViewGroup && (0 until view.childCount).any { hasPasswordInput(view.getChildAt(it)) }
    }
    @Test fun cipherPersistenceEnableAndTamperFailClosed() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val cipher = TestCipher(); val access = DeviceUnlock(app, cipher)
        assertTrue(access.required)
        access.setRequired(false, "synthetic-secret-123")
        val restored = DeviceUnlock(app, cipher)
        assertFalse(restored.required); assertEquals("synthetic-secret-123", restored.password())
        val prefs = app.getSharedPreferences("vault_access", 0)
        val encoded = prefs.getString("state", null)!!
        assertFalse(encoded.contains("synthetic"))
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        assertFalse(String(bytes, Charsets.UTF_8).contains("synthetic-secret-123"))
        restored.setRequired(true, null)
        assertTrue(DeviceUnlock(app, cipher).required); assertNull(DeviceUnlock(app, cipher).password())
        prefs.edit().putString("state", "invalid cipher fixture").commit()
        assertTrue(DeviceUnlock(app, cipher).required); assertNull(DeviceUnlock(app, cipher).password())
    }
    @Test fun processRestartMiniAndFullAutoOpenThenRequiredModeStopsAutoOpen() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val cipher = TestCipher(); val access = DeviceUnlock(app, cipher)
        val store = VaultStore(app, access); app.store = store
        store.create("synthetic-master-123"); wait(store)
        store.setMasterPasswordRequired(false); wait(store)
        store.lock()
        val restarted = VaultStore(app, DeviceUnlock(app, cipher)); app.store = restarted
        wait(restarted)
        assertNotNull(restarted.database); assertFalse(restarted.needsPassword)
        val full = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(hasPasswordInput(full.get().window.decorView))
        full.pause().stop().destroy()
        restarted.lock()
        val mini = QuickSearchPanel(app, restarted, { _, _ -> }, { _, _ -> }, {})
        wait(restarted)
        assertNotNull(restarted.database); assertFalse(hasPasswordInput(mini))
        mini.dispose()
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.MINUTES)
        assertNotNull(restarted.database)
        restarted.setMasterPasswordRequired(true); wait(restarted); restarted.lock()
        val protected = VaultStore(app, DeviceUnlock(app, cipher)); app.store = protected
        wait(protected)
        assertNull(protected.database); assertTrue(protected.needsPassword)
        val locked = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(hasPasswordInput(locked.get().window.decorView))
        locked.pause().stop().destroy()
    }
}
