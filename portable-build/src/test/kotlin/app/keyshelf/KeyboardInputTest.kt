package app.keyshelf

import android.app.Activity
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class KeyboardInputTest {
    @Test fun waitsForWindowFocusAndDoesNotReopenKeyboardAfterSuccess() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val input = Ui.input(activity, "Найти сервис…", "saved query")
        activity.setContentView(input)
        controller.windowFocusChanged(false)
        val ime = activity.getSystemService(InputMethodManager::class.java)
        input.focusKeyboard()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(input.isFocused)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertTrue(shadowOf(ime).isSoftInputVisible)
        assertEquals("saved query", input.text.toString())
        ime.hideSoftInputFromWindow(input.windowToken, 0)
        controller.windowFocusChanged(false); controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        controller.pause().stop().destroy()
    }
    @Test fun detachedOrCancelledInputCannotOpenKeyboardLater() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val input = Ui.input(activity, "Поле")
        activity.setContentView(input)
        controller.windowFocusChanged(false)
        input.focusKeyboard(); input.cancelKeyboard()
        controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible)
        controller.pause().stop().destroy()
    }
}
