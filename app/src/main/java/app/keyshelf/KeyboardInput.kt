package app.keyshelf

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** One explicit keyboard request, deferred until this input's window can accept it. */
class KeyboardInput(context: Context) : EditText(context) {
    private var pending = false
    private var attempts = 0
    private var allowed: () -> Boolean = { true }
    private val showKeyboard = object : Runnable {
        override fun run() {
            if (!pending) return
            if (!allowed() || !isEnabled || !isShown || !isFocused) { cancelKeyboard(); return }
            if (!isAttachedToWindow || !hasWindowFocus()) return
            attempts++
            val shown = context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(this@KeyboardInput, InputMethodManager.SHOW_IMPLICIT)
            if (shown || attempts >= 3) cancelKeyboard() else postDelayed(this, 120)
        }
    }
    fun focusKeyboard(valid: () -> Boolean = { true }) {
        cancelKeyboard(); allowed = valid; pending = true; attempts = 0
        showSoftInputOnFocus = true
        requestFocus(); post(showKeyboard)
    }
    fun cancelKeyboard() { pending = false; removeCallbacks(showKeyboard); allowed = { true } }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pending) { requestFocus(); post(showKeyboard) }
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && pending) { requestFocus(); removeCallbacks(showKeyboard); post(showKeyboard) }
    }
    override fun onDetachedFromWindow() { cancelKeyboard(); super.onDetachedFromWindow() }
}
