package app.keyshelf

import android.Manifest
import android.content.Context
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = KeyShelfApp::class)
class FloatingRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private val own get() = android.content.ComponentName(app, FloatingService::class.java).flattenToString()
    private fun selected() = Settings.Secure.getString(app.contentResolver, key)
    private fun waitFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        app.getSharedPreferences("floating_recovery", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(app.contentResolver, key, "other/service:$own")
    }
    @Test fun disconnectedSelectionIsRestoredAndConcurrentOtherChangesPreserved() {
        FloatingRecovery.check(app); waitFor(1000)
        assertEquals("other/service", selected())
        Settings.Secure.putString(app.contentResolver, key, "other/service:new/service")
        waitFor(600)
        assertEquals("other/service:new/service:$own", selected())
        assertFalse(app.getSharedPreferences("floating_recovery", 0).contains("pending"))
    }
    @Test fun disabledServiceRemainsDisabled() {
        Settings.Secure.putString(app.contentResolver, key, "other/service")
        FloatingRecovery.check(app); waitFor(1700)
        assertEquals("other/service", selected())
    }
    @Test fun globallyDisabledAccessibilityIsNotEnabled() {
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        FloatingRecovery.check(app); waitFor(1700)
        assertEquals("other/service:$own", selected())
        assertEquals(0, Settings.Secure.getInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED))
    }
    @Test fun interruptedRestoreSurvivesNextLaunch() {
        Settings.Secure.putString(app.contentResolver, key, "other/service")
        app.getSharedPreferences("floating_recovery", 0).edit().putBoolean("pending", true).commit()
        FloatingRecovery.check(app); waitFor(1000)
        assertEquals("other/service:$own", selected())
    }
    @Test fun noSpecialPermissionDoesNotWriteSettings() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        FloatingRecovery.check(app); waitFor(1700)
        assertEquals("other/service:$own", selected())
    }
}
