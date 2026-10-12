package app.keyshelf

import android.app.Activity
import android.app.AlertDialog
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordScrollTest {
    @Test fun longFormScrollsAndKeepsDialogActionsInsideViewport() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val fields = Ui.panel(activity)
        repeat(30) { fields.addView(Ui.input(activity, "Synthetic field $it")) }
        val scroll = Ui.scroll(activity, fields)
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert).setTitle("Synthetic editor").setView(scroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.show()
        // Robolectric's AlertController uses a synthetic ScrollView instead of
        // the platform panel hierarchy. Exercise the real framework measurer.
        val platformPanels = Class.forName("com.android.internal.widget.AlertDialogLayout")
            .getConstructor(android.content.Context::class.java).newInstance(activity) as LinearLayout
        platformPanels.orientation = LinearLayout.VERTICAL
        fun id(name: String) = activity.resources.getIdentifier(name, "id", "android")
        platformPanels.addView(TextView(activity).apply { this.id = id("topPanel"); text = "Synthetic editor" })
        (scroll.parent as ViewGroup).removeView(scroll)
        platformPanels.addView(FrameLayout(activity).apply {
            this.id = id("customPanel"); addView(scroll)
        })
        val originalButtons = LinearLayout(activity).apply { this.id = id("buttonPanel") }
        for (which in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)) {
            val button = dialog.getButton(which)
            (button.parent as ViewGroup).removeView(button); originalButtons.addView(button)
        }
        platformPanels.addView(originalButtons)
        dialog.window!!.setContentView(platformPanels)
        Ui.recordDialog(dialog)
        val buttons = dialog.getButton(AlertDialog.BUTTON_POSITIVE).parent as View
        val panels = buttons.parent as ViewGroup
        val width = View.MeasureSpec.makeMeasureSpec(Ui.dp(activity, 360), View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(Ui.dp(activity, 500), View.MeasureSpec.AT_MOST)
        panels.measure(width, height)
        panels.layout(0, 0, panels.measuredWidth, panels.measuredHeight)
        assertTrue("Actions must keep their full height", buttons.height >= Ui.dp(activity, 68))
        assertTrue("Actions must fit inside the dialog", buttons.bottom <= panels.height)
        assertTrue("Content must exceed its bounded scroll viewport: fields=${fields.height}, scroll=${scroll.height}, panels=${panels.javaClass.simpleName}/${panels.height}, buttons=${buttons.id}/${buttons.height}", fields.height > scroll.height)
        scroll.scrollTo(0, 0)
        val x = (scroll.width * .5f)
        val start = (scroll.height * .8f)
        fun touch(action: Int, time: Long, y: Float) {
            val event = MotionEvent.obtain(1000L, time, action, x, y, 0)
            scroll.dispatchTouchEvent(event); event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 1000L, start)
        touch(MotionEvent.ACTION_MOVE, 1040L, start - Ui.dp(activity, 40))
        touch(MotionEvent.ACTION_MOVE, 1080L, start - Ui.dp(activity, 140))
        touch(MotionEvent.ACTION_UP, 1120L, start - Ui.dp(activity, 140))
        assertTrue("Vertical swipe must scroll the form", scroll.scrollY > 0)
        scroll.fullScroll(ScrollView.FOCUS_DOWN)
        assertTrue(scroll.scrollY > 0)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
}
