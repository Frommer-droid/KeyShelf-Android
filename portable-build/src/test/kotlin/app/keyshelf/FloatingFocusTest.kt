package app.keyshelf

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = KeyShelfApp::class)
class FloatingFocusTest {
    @Test fun connectedFloatingServiceTakesPriorityOverAutofillUntilDisconnected() {
        assertTrue(FillService.shouldOfferAutofill())
        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        try {
            service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
            assertFalse(service.buttonVisible) // Priority applies before the first focus event.
            assertFalse(FillService.shouldOfferAutofill())
            service.hideForApplicationUi()
            assertFalse(FillService.shouldOfferAutofill()) // Opening Main must not revive the popup.
        } finally { controller.destroy() }
        assertTrue(FillService.shouldOfferAutofill()) // Autofill remains usable on its own.
    }
    private fun window(id: Int, type: Int, pkg: String, editable: Boolean, focused: Boolean = true): Pair<AccessibilityWindowInfo, AccessibilityNodeInfo> {
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(id); shadowOf(window).setType(type)
        shadowOf(window).setFocused(focused); shadowOf(window).setActive(focused)
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg; isEditable = editable; isFocused = focused
            isEnabled = true; isVisibleToUser = true; setBoundsInScreen(Rect(10, 80, 300, 140))
        }
        shadowOf(root).setAccessibilityWindowInfo(window)
        shadowOf(root).setRefreshReturnValue(true)
        AccessibilityNodeInfo::class.java.getMethod("setSealed", Boolean::class.javaPrimitiveType).invoke(root, true)
        shadowOf(window).setRoot(root)
        return window to root
    }
    private fun event(type: Int, pkg: String?, windowId: Int, node: AccessibilityNodeInfo? = null): AccessibilityEvent {
        val result = AccessibilityEvent.obtain(type).apply { packageName = pkg }
        shadowOf(result).setWindowId(windowId)
        if (node != null) shadowOf(result).setSourceNode(node)
        return result
    }
    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS) }
    private fun icon(service: FloatingService) = service.javaClass.getDeclaredField("button").apply { isAccessible = true }.get(service) as View
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 20, action, x, y, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun draggingKeepsIconAndSavedPositionAcrossFocusAndServiceRestart() {
        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        val (app, editor) = window(10, AccessibilityWindowInfo.TYPE_APPLICATION, "example.editor", true)
        shadowOf(service).setWindows(listOf(app))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, "example.editor", 10, editor))
        val view = icon(service); val p = view.layoutParams as WindowManager.LayoutParams
        val initial = p.x to p.y
        touch(view, MotionEvent.ACTION_DOWN, 220f, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 120f, 240f)
        touch(view, MotionEvent.ACTION_UP, 120f, 240f)
        val moved = p.x to p.y
        assertNotEquals(initial, moved); assertTrue(service.buttonVisible)
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, "example.editor", 10, editor))
        assertSame(view, icon(service)); assertEquals(moved, p.x to p.y)
        controller.destroy()
        val restarted = Robolectric.buildService(FloatingService::class.java).create()
        shadowOf(restarted.get()).setWindows(listOf(app))
        restarted.get().onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, "example.editor", 10, editor))
        val restored = icon(restarted.get()).layoutParams as WindowManager.LayoutParams
        assertEquals(moved, restored.x to restored.y)
        val small = icon(restarted.get())
        touch(small, MotionEvent.ACTION_DOWN, 200f, 100f)
        touch(small, MotionEvent.ACTION_UP, 201f, 101f)
        assertFalse(restarted.get().buttonVisible) // Tiny movement still opens search, not a drag.
        restarted.destroy()
    }
    @Test fun dragClampsToScreenAndCancelDoesNotOpenSearch() {
        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        val (app, editor) = window(10, AccessibilityWindowInfo.TYPE_APPLICATION, "example.editor", true)
        shadowOf(service).setWindows(listOf(app))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, "example.editor", 10, editor))
        val view = icon(service); val p = view.layoutParams as WindowManager.LayoutParams
        touch(view, MotionEvent.ACTION_DOWN, 120f, 120f)
        touch(view, MotionEvent.ACTION_MOVE, -10000f, 10000f)
        touch(view, MotionEvent.ACTION_CANCEL, -10000f, 10000f)
        assertEquals(FloatingPosition.clamp(service, p.width, Int.MAX_VALUE, Int.MAX_VALUE), p.x to p.y)
        assertTrue(service.buttonVisible)
        val metrics = service.resources.displayMetrics
        val originalWidth = metrics.widthPixels; val originalHeight = metrics.heightPixels
        try {
            metrics.widthPixels = originalHeight; metrics.heightPixels = originalWidth
            val rotated = FloatingPosition.load(service, p.width)!!
            assertEquals(rotated, FloatingPosition.clamp(service, p.width, rotated.first, rotated.second))
        } finally { metrics.widthPixels = originalWidth; metrics.heightPixels = originalHeight }
        controller.destroy()
    }
    @Test fun ownOverlayAndKeyboardEventsDoNotHideButton() {
        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        val (app, editor) = window(10, AccessibilityWindowInfo.TYPE_APPLICATION, "example.editor", true)
        val (ime, key) = window(20, AccessibilityWindowInfo.TYPE_INPUT_METHOD, "example.keyboard", false)
        val (overlay, _) = window(30, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "app.keyshelf", false)
        shadowOf(service).setWindows(listOf(app, ime, overlay))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, "example.editor", 10, editor))
        assertTrue(service.buttonVisible)
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "app.keyshelf", 30))
        assertTrue(service.buttonVisible)
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "app.keyshelf", -1))
        assertTrue(service.buttonVisible)
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, "example.keyboard", 20, key))
        settle(); assertTrue(service.buttonVisible)
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, 20))
        settle(); assertTrue(service.buttonVisible)
        controller.destroy()
    }
    @Test fun realApplicationSwitchRemovesOldTarget() {
        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        val (app, editor) = window(10, AccessibilityWindowInfo.TYPE_APPLICATION, "example.editor", true)
        shadowOf(service).setWindows(listOf(app))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, "example.editor", 10, editor))
        assertTrue(service.buttonVisible)
        val (other, _) = window(40, AccessibilityWindowInfo.TYPE_APPLICATION, "example.other", false)
        shadowOf(app).setActive(false); shadowOf(app).setFocused(false)
        shadowOf(service).setWindows(listOf(other, app))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "example.other", 40))
        settle(); assertFalse(service.buttonVisible)
        controller.destroy()
    }
    @Test fun alreadyFocusedEditorIsFoundOnWindowRefresh() {
        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        val (app, _) = window(10, AccessibilityWindowInfo.TYPE_APPLICATION, "example.editor", true)
        shadowOf(service).setWindows(listOf(app))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "android", 10))
        settle(); assertTrue(service.buttonVisible)
        controller.destroy()
    }
}
