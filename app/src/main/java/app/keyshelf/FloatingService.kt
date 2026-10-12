package app.keyshelf

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

class FloatingService : AccessibilityService() {
    private val store get() = (application as KeyShelfApp).store
    private val manager get() = getSystemService(WindowManager::class.java)
    private var button: View? = null
    private var buttonGestureActive = false
    internal val buttonVisible get() = button != null
    private var panel: QuickSearchPanel? = null
    private var editing = false
    private var target: AccessibilityNodeInfo? = null
    private var targetPackage = ""
    private var targetWindow = -1
    private val focusHandler = Handler(Looper.getMainLooper())
    private var refreshPending = false
    private val refreshFocus = Runnable { refreshPending = false; reconcileFocus() }
    override fun onServiceConnected() { instance = this; scheduleFocusRefresh() }
    override fun onDestroy() {
        focusHandler.removeCallbacksAndMessages(null)
        closeSearch(); removeButton(); target = null
        if (instance === this) instance = null
        super.onDestroy()
    }
    override fun onInterrupt() { hideForApplicationUi() }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (panel != null || editing) return
        // Adding the K overlay generates events from KeyShelf itself. They are
        // feedback from our own window, not a change to the user's text field.
        if (event.packageName?.toString() == packageName) return
        val type = windows.firstOrNull { it.id == event.windowId }?.type
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            type == AccessibilityWindowInfo.TYPE_SYSTEM) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val node = event.source ?: return
            if (usable(node)) adopt(node) else scheduleFocusRefresh()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && target != null) {
            scheduleFocusRefresh()
        }
    }
    private fun usable(node: AccessibilityNodeInfo) = node.isEditable && node.isEnabled &&
        node.isVisibleToUser && node.packageName?.toString() != packageName
    private fun adopt(node: AccessibilityNodeInfo) {
        if (!node.refresh() || !usable(node)) return
        target = AccessibilityNodeInfo.obtain(node)
        targetPackage = node.packageName?.toString().orEmpty(); targetWindow = node.windowId
        showButton(node)
    }
    private fun applicationWindow(): AccessibilityWindowInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.filter { it.isFocused || it.isActive }.maxByOrNull { it.layer }
            ?: apps.firstOrNull { it.id == targetWindow }
            ?: apps.maxByOrNull { it.layer }
    }
    private fun focusedEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused && usable(root)) return root
        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?.takeIf { usable(it) }?.let { return it }
        // Some custom views do not implement findFocus reliably. Inspect only
        // focus/editability flags; never read text from other applications.
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count++ < 256) {
            val node = queue.removeFirst()
            if (node.isFocused && usable(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }
    private fun scheduleFocusRefresh() {
        if (!refreshPending) { refreshPending = true; focusHandler.postDelayed(refreshFocus, 120) }
    }
    private fun reconcileFocus() {
        if (panel != null || editing) return
        val app = applicationWindow() ?: return
        val root = app.root ?: return
        if (root.packageName?.toString() == packageName) { clearTarget(); return }
        val editor = focusedEditor(root)
        if (editor != null) adopt(editor) else clearTarget()
    }
    private fun clearTarget() { removeButton(); target = null; targetPackage = ""; targetWindow = -1 }
    fun hideForApplicationUi() {
        editing = false
        focusHandler.removeCallbacksAndMessages(null); refreshPending = false
        closeSearch(); clearTarget()
    }
    private fun params(width: Int, height: Int, focus: Boolean) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE or
            if (focus) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.END; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
    private fun showButton(node: AccessibilityNodeInfo) {
        if (!node.refresh() || !node.isEditable || !node.isVisibleToUser) return
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val p = params(Ui.dp(this, 56), Ui.dp(this, 56), false)
        p.gravity = Gravity.TOP or Gravity.RIGHT
        p.x = Ui.dp(this, 8); p.y = bounds.top.coerceIn(Ui.dp(this, 32), resources.displayMetrics.heightPixels - Ui.dp(this, 140))
        FloatingPosition.load(this, p.width)?.let { (x, y) -> p.x = x; p.y = y }
        val existing = button
        if (existing != null) {
            if (buttonGestureActive) return
            val old = existing.layoutParams as WindowManager.LayoutParams
            if (old.x != p.x || old.y != p.y) { old.x = p.x; old.y = p.y; manager.updateViewLayout(existing, old) }
            return
        }
        val created = FloatingIcon.create(this) { openSearch() }
        attachDrag(created)
        manager.addView(created, p); button = created
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(icon: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        icon.setOnTouchListener { view, event ->
            if (button !== view) return@setOnTouchListener true
            val p = view.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    buttonGestureActive = true; dragged = false
                    downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y
                    view.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (dx * dx + dy * dy > slop * slop) dragged = true
                    if (dragged) {
                        view.isPressed = false
                        val (x, y) = FloatingPosition.clamp(this, p.width, startX - dx.toInt(), startY + dy.toInt())
                        p.x = x; p.y = y; manager.updateViewLayout(view, p)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttonGestureActive = false; view.isPressed = false
                    if (dragged) FloatingPosition.save(this, p.width, p.x, p.y)
                    else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            true
        }
    }
    private fun removeButton() {
        buttonGestureActive = false
        button?.let { runCatching { manager.removeView(it) } }; button = null
    }
    fun closeSearch() {
        val old = panel ?: return
        panel = null
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(old.windowToken, 0)
        old.dispose(); runCatching { manager.removeView(old) }
    }
    private fun openSearch() {
        closeSearch(); removeButton()
        val content = QuickSearchPanel(this, store, { _, value -> insert(value) },
            ::editRecord, { closeSearch(); target?.let(::showButton) }, state = store.quickSearchState) {
            panel?.let { active -> active.post {
                if (panel === active && active.isAttachedToWindow)
                    manager.updateViewLayout(active, active.layoutParams)
            } }
        }
        val width = minOf(resources.displayMetrics.widthPixels - Ui.dp(this, 24), Ui.dp(this, 440))
        val p = params(width, -2, true).apply {
            x = Ui.dp(this@FloatingService, 12); y = Ui.dp(this@FloatingService, 48)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        manager.addView(content, p); panel = content
        content.focusSearch()
    }
    private fun editRecord(row: VaultRow, field: String) {
        if (store.database == null) return
        closeSearch(); removeButton(); editing = true
        val intent = MiniEditActivity.intent(this, row, field).putExtra("from_overlay", true)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            editing = false; openSearch()
            Toast.makeText(this, "Не удалось открыть редактор", Toast.LENGTH_LONG).show()
        }
    }
    fun finishMiniEdit() {
        if (!editing) return
        val originalPackage = targetPackage
        val originalWindow = targetWindow
        fun restore(attempt: Int) {
            if (instance !== this || !editing) return
            // Wait for the floating editor's closing animation, so its own
            // application window does not erase the original text-field target.
            if (applicationWindow()?.root?.packageName?.toString() == packageName && attempt < 6) {
                focusHandler.postDelayed({ restore(attempt + 1) }, 120)
                return
            }
            editing = false
            val app = applicationWindow()
            val returnedToOriginal = app?.id == originalWindow && app.root?.packageName?.toString() == originalPackage
            reconcileFocus()
            if (returnedToOriginal && target != null && store.database != null) openSearch()
        }
        focusHandler.postDelayed({ restore(0) }, 200)
    }
    private fun insert(value: String) {
        if (store.database == null) return
        val node = target ?: run { failed(); return }
        val expectedPackage = targetPackage; val expectedWindow = targetWindow
        closeSearch()
        Handler(Looper.getMainLooper()).postDelayed({
            val app = applicationWindow()
            val root = app?.root
            if (store.database == null || root?.packageName?.toString() != expectedPackage || app.id != expectedWindow ||
                !node.refresh() || !node.isFocused || !node.isEditable || !node.isEnabled || !node.isVisibleToUser || node.packageName?.toString() != expectedPackage) {
                failed(); return@postDelayed
            }
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) failed("Поле не поддерживает вставку. Копирование доступно в полном режиме.")
            else { store.touch(); showButton(node) }
        }, 200)
    }
    private fun failed(message: String = "Выберите поле ввода и повторите вставку.") { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    companion object { var instance: FloatingService? = null; private set }
}

/** Device-local placement as screen fractions, so rotation/resolution changes stay in bounds. */
internal object FloatingPosition {
    private fun limits(c: android.content.Context, size: Int): IntArray {
        val margin = Ui.dp(c, 8); val top = Ui.dp(c, 32)
        return intArrayOf(margin, (c.resources.displayMetrics.widthPixels - size - margin).coerceAtLeast(margin),
            top, (c.resources.displayMetrics.heightPixels - size - Ui.dp(c, 24)).coerceAtLeast(top))
    }
    fun clamp(c: android.content.Context, size: Int, x: Int, y: Int): Pair<Int, Int> {
        val l = limits(c, size)
        return x.coerceIn(l[0], l[1]) to y.coerceIn(l[2], l[3])
    }
    fun save(c: android.content.Context, size: Int, x: Int, y: Int) {
        val l = limits(c, size); val (safeX, safeY) = clamp(c, size, x, y)
        c.getSharedPreferences("floating_position", 0).edit()
            .putFloat("x", (safeX - l[0]).toFloat() / (l[1] - l[0]).coerceAtLeast(1))
            .putFloat("y", (safeY - l[2]).toFloat() / (l[3] - l[2]).coerceAtLeast(1)).apply()
    }
    fun load(c: android.content.Context, size: Int): Pair<Int, Int>? {
        val prefs = c.getSharedPreferences("floating_position", 0)
        if (!prefs.contains("x") || !prefs.contains("y")) return null
        val x = prefs.getFloat("x", 0f); val y = prefs.getFloat("y", 0f)
        if (!x.isFinite() || !y.isFinite()) return null
        val l = limits(c, size)
        return clamp(c, size, l[0] + kotlin.math.round(x.coerceIn(0f, 1f) * (l[1] - l[0])).toInt(),
            l[2] + kotlin.math.round(y.coerceIn(0f, 1f) * (l[3] - l[2])).toInt())
    }
}

object FloatingIcon {
    fun create(context: android.content.Context, imageResource: Int = R.drawable.ic_keyshelf, open: () -> Unit) = android.widget.ImageButton(context).apply {
        setImageResource(imageResource)
        contentDescription = "Найти запись в KeyShelf"
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val padding = Ui.dp(context, 3); setPadding(padding, padding, padding, padding)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.background)
        }
        backgroundTintList = null; imageTintList = null
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val diameter = minOf(view.width, view.height)
                val left = (view.width - diameter) / 2
                val top = (view.height - diameter) / 2
                outline.setOval(left, top, left + diameter, top + diameter)
            }
        }
        clipToOutline = true; elevation = 0f; stateListAnimator = null
        setOnClickListener { open() }
    }
}
