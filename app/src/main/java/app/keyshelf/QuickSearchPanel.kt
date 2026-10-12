package app.keyshelf

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Read-only mini mode shared by the K overlay and Android Autofill. */
class QuickSearchPanel(
    context: Context,
    private val store: VaultStore,
    private val pick: (VaultRow, String) -> Unit,
    private val edit: (VaultRow, String) -> Unit,
    private val close: () -> Unit,
    private val state: QuickSearchState = QuickSearchState(),
    private val sizeChanged: () -> Unit = {}
) : LinearLayout(context) {
    val search = Ui.input(context, "Поиск по записям").apply {
        background = null; textSize = 15f; minimumHeight = Ui.dp(context, 48)
        setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 4), 0)
    }
    val results = LinearLayout(context).apply { orientation = VERTICAL }
    val resultsScroll = ScrollView(context).apply { addView(results); visibility = GONE }
    private val searchDivider = View(context).apply { setBackgroundColor(Ui.accent); visibility = GONE }
    private val unlock = LinearLayout(context).apply { orientation = VERTICAL }
    private val password = Ui.input(context, "Мастер-пароль", secret = true)
    private val unlockButton = Ui.button(context, "Разблокировать") {
        if (store.needsPassword) store.unlock(password.text.toString()) else store.autoUnlock()
        password.text.clear()
    }
    private val feedback = Ui.label(context, "", muted = true)
    private var expanded: String? = state.expandedId
    private var favoritesOnly = state.favoritesOnly
    private val favoritesButton = headerIcon(R.drawable.ic_star_outline, "Избранное") {
        favoritesOnly = !favoritesOnly
        store.touch(); updateFavoritesButton(); renderResults()
    }
    private var lastUnlocked = store.database != null
    private var changing = false
    private var disposed = false
    private val handler = Handler(Looper.getMainLooper())
    private val liveCodes = mutableListOf<Pair<TextView, () -> String>>()
    private var displayedDatabase: app.keemobile.kotpass.database.KeePassDatabase? = null
    private val changes: () -> Unit = {
        if (!store.syncNotification || store.database !== displayedDatabase) refresh()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (disposed) return
            liveCodes.forEach { (display, code) -> display.text = runCatching(code).getOrDefault("—") }
            handler.postDelayed(this, 1000)
        }
    }
    init {
        orientation = VERTICAL
        val inset = Ui.dp(context, 2); setPadding(inset, inset, inset, inset)
        background = Ui.rounded(context, Ui.background, 16).apply {
            setStroke(Ui.dp(context, 1).coerceAtLeast(1), Ui.accent)
        }
        clipToOutline = true
        val line = LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL; background = Ui.rounded(context, Ui.surface, 14)
            clipToOutline = true
            setPadding(0, 0, 0, 0)
        }
        line.addView(search, LayoutParams(0, Ui.dp(context, 48), 1f))
        line.addView(headerIcon(R.drawable.ic_erase, "Очистить поиск") { search.text.clear(); search.requestFocus() })
        line.addView(favoritesButton)
        line.addView(headerIcon(R.drawable.ic_clear, "Закрыть поиск") { close() })
        addView(line, LayoutParams(-1, Ui.dp(context, 48)))
        // Search and results share the outer outline; only their boundary is drawn inside.
        addView(searchDivider, LayoutParams(-1, Ui.dp(context, 1).coerceAtLeast(1)))
        unlock.setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
        Ui.dialogAction(unlockButton, primary = true)
        unlock.addView(password)
        unlock.addView(unlockButton)
        addView(unlock)
        addView(feedback)
        addView(resultsScroll, LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 4) })
        search.contentDescription = "Поиск по всему хранилищу"
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!changing) { expanded = null; store.touch(); renderResults() }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        search.setOnEditorActionListener { _, _, _ -> true }
        store.listen(changes)
        store.autoUnlock()
        changing = true
        search.setText(state.query)
        search.setSelection(search.text.length)
        changing = false
        refresh()
        val savedQuery = state.query
        val savedScroll = state.scrollY
        resultsScroll.post {
            if (!disposed && search.text.toString() == savedQuery) resultsScroll.scrollTo(0, savedScroll)
        }
    }
    private fun headerIcon(resource: Int, name: String, action: () -> Unit) = Ui.iconButton(context, resource, name, action).apply {
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(30, 255, 255, 255)), Ui.rounded(context), null)
        scaleType = ImageView.ScaleType.FIT_CENTER
        // The close path occupies less of its viewport than the star/backspace.
        val inset = Ui.dp(context, if (resource == R.drawable.ic_clear) 8 else 12)
        setPadding(inset, inset, inset, inset)
    }
    private fun icon(text: String, name: String, action: () -> Unit) = Ui.button(context, text, action).apply {
        contentDescription = name
        minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
        textSize = 22f; backgroundTintList = null
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(30, 255, 255, 255)), Ui.rounded(context), null)
        layoutParams = LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48))
    }
    private fun updateFavoritesButton() {
        favoritesButton.setImageResource(if (favoritesOnly) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        favoritesButton.isSelected = favoritesOnly
        favoritesButton.imageTintList = android.content.res.ColorStateList.valueOf(if (favoritesOnly) Ui.favorite else Ui.text)
        favoritesButton.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(30, 255, 255, 255)),
            Ui.rounded(context, if (favoritesOnly) android.graphics.Color.rgb(62, 52, 47) else Ui.surface).apply {
                if (favoritesOnly) setStroke(Ui.dp(context, 1).coerceAtLeast(1), Ui.favorite)
            }, null)
        favoritesButton.isEnabled = store.database != null
    }
    fun refresh() {
        if (disposed) return
        val unlocked = store.database != null
        val justUnlocked = !lastUnlocked && unlocked
        if (lastUnlocked && !unlocked) {
            changing = true; search.text.clear(); changing = false; expanded = null
        }
        if (!unlocked) {
            state.clear(); changing = true; search.text.clear(); changing = false; expanded = null; favoritesOnly = false
        }
        if (!lastUnlocked && unlocked) password.text.clear()
        lastUnlocked = unlocked
        search.isEnabled = unlocked
        unlock.visibility = if (unlocked || !store.file.exists()) GONE else VISIBLE
        unlockButton.isEnabled = !store.busy
        password.visibility = if (store.needsPassword) VISIBLE else GONE
        unlockButton.text = if (store.needsPassword) "Разблокировать" else "Открыть хранилище"
        feedback.text = when {
            store.busy && !unlocked -> "Разблокировка…"
            !unlocked && store.error.isNotEmpty() -> store.error
            !unlocked && !store.file.exists() -> "Откройте хранилище через значок KeyShelf."
            else -> ""
        }
        feedback.visibility = if (feedback.text.isEmpty()) GONE else VISIBLE
        updateFavoritesButton()
        renderResults()
        if (justUnlocked) post { focusSearch() }
    }
    private fun scrub(view: View) {
        if (view is TextView) view.text = ""
        if (view is ViewGroup) for (i in 0 until view.childCount) scrub(view.getChildAt(i))
    }
    private fun renderResults() {
        val savedScroll = if (store.syncNotification) resultsScroll.scrollY else null
        displayedDatabase = store.database
        liveCodes.clear()
        scrub(results); results.removeAllViews()
        val db = store.database
        val query = search.text.toString()
        if (db == null || query.isBlank() && !favoritesOnly) {
            searchDivider.visibility = if (unlock.visibility == VISIBLE || feedback.visibility == VISIBLE) VISIBLE else GONE
            resultsScroll.visibility = GONE; sizeChanged(); return
        }
        val matches = VaultCodec.search(VaultCodec.rows(db), query).filter { !favoritesOnly || it.favorite }
        resultsScroll.visibility = VISIBLE
        searchDivider.visibility = VISIBLE
        if (matches.isEmpty()) results.addView(Ui.label(context,
            if (favoritesOnly && query.isBlank()) "Нет избранных записей"
            else "Ничего не найдено", muted = true).apply {
                textSize = 14f; gravity = android.view.Gravity.CENTER
                setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12))
                layoutParams = LayoutParams(-1, -2)
            })
        matches.forEach { row ->
            val card = Ui.card(context).apply {
                setPadding(0, 0, 0, Ui.dp(context, 4))
                background = Ui.rounded(context, Ui.typeSurface(row.kind)); clipToOutline = true
                layoutParams = LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 8) }
            }
            val title = "${row.service} · ${row.title}"
            val isExpanded = expanded == row.id
            val header = Ui.recordCard(context, row.title, row.service, row.favorite, row.kind, row.meta.optBoolean("api_paid")) {
                expanded = if (expanded == row.id) null else row.id
                store.touch(); renderResults()
            }.apply {
                contentDescription = if (isExpanded) "Свернуть запись $title" else "Раскрыть запись $title"
                layoutParams = LayoutParams(-1, -2)
                getChildAt(childCount - 1).rotation = if (isExpanded) 90f else 0f
            }
            card.addView(header)
            if (isExpanded) addValues(card, row)
            results.addView(card)
        }
        val maxHeight = (resources.displayMetrics.heightPixels * 0.38).toInt().coerceAtMost(Ui.dp(context, 340))
        resultsScroll.layoutParams = LayoutParams(-1, -2)
        resultsScroll.measure(MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        resultsScroll.layoutParams = LayoutParams(-1, resultsScroll.measuredHeight.coerceAtMost(maxHeight))
        sizeChanged()
        if (savedScroll != null) resultsScroll.post { if (!disposed) resultsScroll.scrollTo(0, savedScroll) }
    }
    private fun addValues(card: LinearLayout, row: VaultRow) {
        val values = mutableListOf<Pair<String, () -> String>>()
        row.displayFields.filter { it.value.isNotEmpty() && (it.label != "Секрет 2FA" || row.kind != "account") }.forEach { field ->
            values.add(field.label to { field.value })
        }
        values.forEach { (name, value) -> valueRow(card, row, name, value,
            secret = row.displayFields.firstOrNull { it.label == name }?.secret == true && row.kind != "other") }
        row.displayFields.firstOrNull { row.kind == "account" && it.label == "Секрет 2FA" && it.value.isNotBlank() }?.let { seed ->
            if (runCatching { Totp.code(seed.value) }.isSuccess) valueRow(card, row, "Код 2FA", { Totp.code(seed.value) }, true)
            else card.addView(Ui.label(context, "Некорректный секрет 2FA. Исправьте его в полном режиме.", muted = true))
        }
    }
    private fun valueRow(card: LinearLayout, row: VaultRow, name: String, value: () -> String, clock: Boolean = false, secret: Boolean = false) {
        val line = LinearLayout(context).apply {
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0)
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 8) }
        }
        val display = Ui.label(context, if (secret && store.hideRecordSecrets) "••••••••" else value()).apply {
            contentDescription = name
            tooltipText = "Вставить $name"
            setSingleLine(true); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
            minimumHeight = Ui.dp(context, 48); gravity = android.view.Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.background); cornerRadius = Ui.dp(context, 10).toFloat()
            }
            isFocusable = true
            setOnClickListener {
                if (disposed || store.database == null) return@setOnClickListener
                runCatching { pick(row, value()) }.onFailure {
                    Toast.makeText(context, "Не удалось вставить. Повторно выберите поле ввода.", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (clock) liveCodes.add(display to value)
        line.addView(display, LayoutParams(0, -2, 1f))
        line.addView(icon("✎", "Редактировать $name") { edit(row, name) }.apply {
            (layoutParams as LayoutParams).marginStart = Ui.dp(context, 8)
        })
        card.addView(line)
    }
    fun focusSearch() {
        if (store.database == null && !store.needsPassword) return
        val input = if (store.database == null && unlock.visibility == VISIBLE) password else search
        if (!input.isEnabled) return
        input.focusKeyboard { !disposed && input.isEnabled && input.isShown }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }
    fun dispose() {
        if (disposed) return
        if (store.database != null) {
            state.query = search.text.toString(); state.expandedId = expanded; state.scrollY = resultsScroll.scrollY
            state.favoritesOnly = favoritesOnly
        } else state.clear()
        disposed = true; store.unlisten(changes); handler.removeCallbacksAndMessages(null)
        search.cancelKeyboard(); password.cancelKeyboard()
        liveCodes.clear(); password.text.clear(); scrub(results); results.removeAllViews()
        changing = true; search.text.clear(); changing = false
    }
}
