package app.keyshelf

import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = KeyShelfApp::class)
class BootReceiverTest {
    @Test fun bootStartsBackgroundWorkWithoutActivityOrUnlockingProtectedVault() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val receiver = BootReceiver()
        app.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BOOT_COMPLETED))
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(app.store.database)
        assertTrue(app.store.masterPasswordRequired)
        app.unregisterReceiver(receiver)
    }
    @Test fun unrelatedBroadcastDoesNothing() {
        val app = RuntimeEnvironment.getApplication()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
