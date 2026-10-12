package app.keyshelf

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.Group
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private val store get() = (application as KeyShelfApp).store
    private var query = ""
    private var favorites = false
    private var kind = "Все"
    private enum class Page { VAULT, SETTINGS, SYNC }
    private var page = Page.VAULT
    private var selectedService: UUID? = null
    private var wasUnlocked = false
    private var waitingForPair = false
    private val sensitiveDialogs = mutableListOf<android.app.Dialog>()
    private var displayedDatabase: app.keemobile.kotpass.database.KeePassDatabase? = null
    private var pageScroll: ScrollView? = null
    private var listScroll: ScrollView? = null
    private var displayedPage: Page? = null
    private var pendingListY: Int? = null
    private var visibleRecordIds = emptyList<String>()
    private var pendingDeletedRecord: Pair<String, List<String>>? = null
    private var selectedRecordId: String? = null
    private var searchInput: EditText? = null
    private var operationFeedback: TextView? = null
    private var syncFeedback: TextView? = null
    private var saveButton: ImageButton? = null
    private val syncButtons = mutableListOf<Pair<Button, () -> Boolean>>()
    private var resumed = false
    private var syncDialog: AlertDialog? = null
    private var syncDialogKey: String? = null
    private val changed: () -> Unit = {
        if (store.syncNotification && store.database === displayedDatabase && page != Page.SYNC) updateSyncState() else render()
        if (resumed) requestSyncNotifications()
        maybeShowSyncReview()
    }
    private fun pageError() = if (page != Page.VAULT && store.error == store.syncError) "" else store.error
    private fun updateSyncState() {
        operationFeedback?.apply {
            text = pageError()
            visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            styleOperationFeedback(this)
        }
        syncFeedback?.text = store.syncSummary
        saveButton?.apply {
            isEnabled = store.hasUnsavedChanges && !store.busy
            alpha = if (isEnabled) 1f else .4f
        }
        syncButtons.forEach { (button, enabled) ->
            button.isEnabled = enabled(); button.alpha = if (button.isEnabled) 1f else .4f
        }
    }
    private var exportFile: File? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.getBooleanExtra("sync_review", false)) page = Page.SYNC
        store.listen(changed); render()
    }
    override fun onDestroy() { closeSensitiveDialogs(); store.unlisten(changed); super.onDestroy() }
    override fun onResume() { super.onResume(); resumed = true; requestSyncNotifications(); maybeShowSyncReview(); FloatingRecovery.check(this); FloatingService.instance?.hideForApplicationUi(); store.autoUnlock(); store.touch(); store.startDelivery() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value); intent = value
        if (value.getBooleanExtra("sync_review", false)) { page = Page.SYNC; render(); maybeShowSyncReview(force = true) }
    }
    private fun requestSyncNotifications() {
        val prefs = getSharedPreferences("sync_notification_permission", 0)
        if (android.os.Build.VERSION.SDK_INT >= 33 && SyncClient.isPaired(this) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 31)
        }
    }
    private fun maybeShowSyncReview(force: Boolean = false) {
        if (syncDialog != null && syncDialogKey != store.pendingSync?.key) syncDialog?.dismiss()
        val proposal = store.pendingSync ?: return
        if (!resumed || store.database == null || store.busy || syncDialog != null ||
            (!force && (!store.needsSyncPrompt || sensitiveDialogs.any { it.isShowing }))) return
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
            .format(java.util.Date(proposal.modifiedAt))
        val builder = AlertDialog.Builder(this)
            .setTitle("Принять изменения с компьютера?")
            .setMessage("База компьютера изменена: $date.\nПринять изменения?")
            .setPositiveButton("Принять") { _, _ -> store.acceptSync(proposal) }
            .setNegativeButton("Отклонить") { _, _ -> store.rejectSync(proposal) }
        val dialog = builder.create(); syncDialog = dialog; syncDialogKey = proposal.key
        dialog.show(); Ui.recordDialog(dialog)
        protect(dialog) { syncDialog = null; syncDialogKey = null }
        SyncAlerts.clear(this)
    }
    override fun onUserInteraction() { super.onUserInteraction(); store.touch() }
    private fun root(): LinearLayout {
        val panel = Ui.panel(this)
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.background) }
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8), 0)
        }
        if (page != Page.VAULT) header.addView(Ui.iconButton(this, R.drawable.ic_back, "Назад") { navigateBack() })
        else header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_keyshelf)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)).apply { marginEnd = Ui.dp(this@MainActivity, 8) })
        val title = when (page) { Page.VAULT -> "KeyShelf"; Page.SETTINGS -> "Настройки"; Page.SYNC -> "Синхронизация" }
        header.addView(Ui.label(this, title, 24f).apply {
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        saveButton = null
        if (page == Page.VAULT) {
            saveButton = Ui.iconButton(this, R.drawable.ic_save, "Сохранить и отправить изменения") { store.saveChanges() }.apply {
                imageTintList = android.content.res.ColorStateList.valueOf(Ui.surface)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(35, 255, 255, 255)),
                    Ui.rounded(this@MainActivity, Ui.accent, 10), null)
                isEnabled = store.database != null && store.hasUnsavedChanges && !store.busy
                alpha = if (isEnabled) 1f else .4f
                contentDescription = "Сохранить и отправить изменения"
            }
            header.addView(saveButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        }
        header.addView(Ui.iconButton(this, R.drawable.ic_lock, "Заблокировать хранилище") {
            page = Page.VAULT; query = ""; selectedService = null; store.lock()
        }.apply { isEnabled = store.database != null; alpha = if (isEnabled) 1f else .35f })
        lateinit var more: ImageButton
        more = Ui.iconButton(this, R.drawable.ic_more, "Меню") { showMenu(more) }
        header.addView(more); shell.addView(header)
        if (store.file.exists()) shell.addView(Ui.label(this, "База: ${store.databaseName} · локальная копия", 12f, muted = true)
            .apply { setPadding(Ui.dp(this@MainActivity, 16), 0, Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 6)) })
        val fixedVaultControls = page == Page.VAULT && store.database != null
        pageScroll = if (fixedVaultControls) null else Ui.scroll(this, panel)
        shell.addView(pageScroll ?: panel, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell)
        val pageError = pageError()
        operationFeedback = Ui.label(this, pageError.ifEmpty { if (store.busy && !store.syncInProgress) "Выполняется операция…" else "" }, 13f, muted = true).apply {
            styleOperationFeedback(this)
            visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            panel.addView(this)
        }
        return panel
    }
    private fun styleOperationFeedback(view: TextView) {
        val offline = page == Page.VAULT && view.text.toString() == "Нет соединения с компьютером."
        view.gravity = if (offline) android.view.Gravity.CENTER else android.view.Gravity.START
        view.textSize = if (offline) 16f else 13f
        view.setTextColor(if (offline) Ui.favorite else Ui.muted)
        view.typeface = android.graphics.Typeface.create(if (offline) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        view.layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun showMenu(anchor: android.view.View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Настройки")
        if (store.database != null && page == Page.VAULT) menu.menu.add(0, 2, 1, "Добавить сервис")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { page = Page.SETTINGS; render(); true }
                2 -> { newService(); true }
                else -> false
            }
        }
        menu.show()
    }
    private fun navigateBack() {
        page = if (page == Page.SYNC) Page.SETTINGS else Page.VAULT
        render()
    }
    @Deprecated("Native Activity back navigation")
    override fun onBackPressed() {
        if (page != Page.VAULT) navigateBack() else super.onBackPressed()
    }
    private fun serviceGroups(): List<Pair<Group, String>> {
        val result = mutableListOf<Pair<Group, String>>()
        fun collect(group: Group, path: String) {
            result.add(group to path)
            group.groups.forEach { collect(it, if (path.isEmpty()) it.name else "$path / ${it.name}") }
        }
        store.database?.content?.group?.let { root ->
            result.add(root to VaultCodec.serviceName(root, root.uuid))
            root.groups.forEach { collect(it, it.name) }
        }
        return result.sortedBy { it.second.lowercase(java.util.Locale.ROOT).replace("ё", "е") }
    }
    private fun render() {
        if (isFinishing) return
        val preserve = displayedPage == page && displayedDatabase != null && store.database != null
        val pageY = pageScroll?.scrollY ?: 0; val listY = pendingListY ?: listScroll?.scrollY ?: 0
        val focused = searchInput?.isFocused == true
        val start = searchInput?.selectionStart ?: 0; val end = searchInput?.selectionEnd ?: 0
        displayedDatabase = store.database
        displayedPage = page
        pendingListY = if (preserve) listY else null
        listScroll = null; searchInput = null; syncFeedback = null; syncButtons.clear()
        val unlocked = store.database != null
        if (wasUnlocked && !unlocked) { closeSensitiveDialogs(); query = ""; selectedService = null; selectedRecordId = null; pendingDeletedRecord = null; visibleRecordIds = emptyList() }
        wasUnlocked = unlocked
        if (waitingForPair && !store.busy) {
            waitingForPair = false
            AlertDialog.Builder(this).setTitle(if (store.error.isEmpty()) "Сопряжение сохранено" else "Не удалось связать компьютер")
                .setMessage(if (store.error.isEmpty()) store.syncStatus else store.error).setPositiveButton("Понятно", null).showStyled()
        }
        val panel = root()
        if (preserve) panel.post {
            if (!isFinishing && store.database != null) {
                pageScroll?.scrollTo(0, pageY)
                if (focused) searchInput?.apply {
                    requestFocus(); setSelection(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
                }
            }
        }
        if (page == Page.SYNC) { syncUi(panel); return }
        if (page == Page.SETTINGS) { settingsUi(panel); return }
        if (store.database == null) {
            val password = Ui.input(this, "Мастер-пароль", secret = true)
            if (store.needsPassword || !store.file.exists()) panel.addView(password)
            if (store.file.exists()) panel.addView(Ui.button(this, "Открыть хранилище") {
                if (store.needsPassword) store.unlock(password.text.toString()) else store.autoUnlock()
                password.text.clear()
            }.apply { isEnabled = !store.busy })
            else {
                val repeat = Ui.input(this, "Повторите мастер-пароль", secret = true)
                panel.addView(repeat)
                panel.addView(Ui.button(this, "Создать хранилище") {
                    if (password.text.toString() != repeat.text.toString()) message("Пароли не совпадают")
                    else { store.create(password.text.toString()); password.text.clear(); repeat.text.clear() }
                })
                panel.addView(Ui.button(this, "Открыть файл KDBX с компьютера") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 10)
                })
            }
        } else {
            val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val groups = serviceGroups()
            if (groups.none { it.first.uuid == selectedService }) selectedService = null
            val services = Ui.selector(this, listOf("Все сервисы") + groups.map { it.second }, "Выбрать сервис").apply {
                setSelection(groups.indexOfFirst { it.first.uuid == selectedService } + 1)
            }
            row.addView(services, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply { marginEnd = Ui.dp(this@MainActivity, 12) })
            val findService = Ui.iconButton(this, R.drawable.ic_search, "Поиск по сервисам") {}
            row.addView(findService, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginEnd = Ui.dp(this@MainActivity, 8) })
            row.addView(Ui.iconButton(this, R.drawable.ic_add, "Добавить запись") { editor(null) }.apply {
                imageTintList = android.content.res.ColorStateList.valueOf(Ui.surface)
                backgroundTintList = null
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(40, 255, 255, 255)),
                    Ui.rounded(this@MainActivity, Ui.accent, 12), null)
                setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12))
            }, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 12) })
            val search = Ui.input(this, "Поиск по записям", query).apply {
                background = null; textSize = 15f; setPadding(0, 0, 0, 0)
            }
            searchInput = search
            val searchRow = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL; background = Ui.rounded(this@MainActivity)
                setPadding(Ui.dp(this@MainActivity, 14), 0, 0, 0)
            }
            searchRow.addView(ImageView(this).apply { setImageResource(R.drawable.ic_search); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)).apply { marginEnd = Ui.dp(this@MainActivity, 10) })
            searchRow.addView(search, LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f))
            val clear = Ui.iconButton(this, R.drawable.ic_clear, "Очистить поиск") { search.setText("") }.apply {
                visibility = if (query.isEmpty()) android.view.View.INVISIBLE else android.view.View.VISIBLE
            }
            searchRow.addView(clear)
            val controls = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val star = Ui.iconButton(this, R.drawable.ic_star_outline, "Избранное") {}
            fun styleFavorites() {
                Ui.styleFavorite(star, favorites)
            }
            styleFavorites()
            val choices = listOf("Все", "Аккаунты", "API", "Другое")
            val types = Ui.selector(this, choices.map { if (it == "Все") "Все типы" else it }, "Тип записей")
            types.setSelection(choices.indexOf(kind).coerceAtLeast(0))
            controls.addView(types, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(star, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginStart = Ui.dp(this@MainActivity, 12) })
            val reset = Ui.iconButton(this, R.drawable.ic_erase, "Сбросить фильтры") {}.apply {
                minimumWidth = Ui.dp(this@MainActivity, 48); minimumHeight = Ui.dp(this@MainActivity, 48)
                backgroundTintList = null
                val shape = Ui.rounded(this@MainActivity, Ui.surface, 12)
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(30, 255, 255, 255)), shape, null)
            }
            controls.addView(reset, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginStart = Ui.dp(this@MainActivity, 8) })
            panel.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 12) })
            panel.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) })
            val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val count = Ui.label(this, "", 13f, muted = true).apply {
                setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 12))
            }
            panel.addView(count)
            val listScroll = Ui.scroll(this, results).apply { contentDescription = "Список записей" }
            this.listScroll = listScroll
            if (preserve) {
                // A mutation emits both busy and completion notifications. Keep
                // the desired offset through consecutive renders before layout.
                listScroll.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                    override fun onLayoutChange(view: android.view.View, l: Int, t: Int, r: Int, b: Int,
                        oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                        listScroll.removeOnLayoutChangeListener(this)
                        if (this@MainActivity.listScroll === listScroll) {
                            pendingListY?.let { listScroll.scrollTo(0, it) }
                            pendingListY = null
                        }
                    }
                })
            }
            panel.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            fun resetListPosition() { pendingListY = null; selectedRecordId = null; pendingDeletedRecord = null; listScroll.scrollTo(0, 0) }
            fun update() {
                results.removeAllViews()
                val db = store.database ?: return
                val rows = VaultCodec.search(VaultCodec.rows(db), query).filter {
                    (query.isNotBlank() || selectedService == null || it.group.uuid == selectedService) && (!favorites || it.favorite) && when (kind) { "Аккаунты" -> it.kind == "account"; "API" -> it.kind == "api"; "Другое" -> it.kind == "other"; else -> true }
                }
                pendingDeletedRecord?.takeIf { !store.busy }?.let { (removed, previous) ->
                    if (VaultCodec.rows(db).none { it.id == removed }) {
                        val index = previous.indexOf(removed)
                        val candidates = previous.drop(index + 1) + previous.take(index.coerceAtLeast(0)).reversed()
                        selectedRecordId = candidates.firstOrNull { id -> rows.any { it.id == id } }
                    }
                    pendingDeletedRecord = null
                }
                visibleRecordIds = rows.map { it.id }
                if (rows.none { it.id == selectedRecordId }) selectedRecordId = null
                count.text = "Записей: ${rows.size}"
                rows.forEach { item ->
                    results.addView(Ui.recordCard(this, item.title, item.service, item.favorite, item.kind, item.meta.optBoolean("api_paid"), selected = item.id == selectedRecordId) { details(item) })
                }
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (query != s.toString()) resetListPosition()
                    query = s.toString(); clear.visibility = if (query.isEmpty()) android.view.View.INVISIBLE else android.view.View.VISIBLE
                    store.touch(); update()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            star.setOnClickListener { favorites = !favorites; resetListPosition(); styleFavorites(); store.touch(); update() }
            types.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (kind != choices[position]) resetListPosition()
                    kind = choices[position]; update()
                }
            }
            services.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val next = groups.getOrNull(position - 1)?.first?.uuid
                    if (selectedService != next) resetListPosition()
                    selectedService = next; store.touch(); update()
                }
            }
            reset.setOnClickListener {
                resetListPosition()
                query = ""; selectedService = null; kind = "Все"; favorites = false
                search.text.clear(); services.setSelection(0); types.setSelection(0); styleFavorites()
                store.touch(); update()
            }
            findService.setOnClickListener {
                chooseService { id ->
                    resetListPosition()
                    selectedService = id
                    services.setSelection(groups.indexOfFirst { it.first.uuid == id } + 1)
                    search.text.clear(); store.touch(); update()
                }
            }
            update()
        }
    }
    private fun chooseService(includeAll: Boolean = true, currentId: UUID? = selectedService, selected: (UUID?) -> Unit) {
        val panel = Ui.panel(this)
        val search = Ui.input(this, "Найти сервис…").apply { contentDescription = "Поиск названия сервиса" }
        panel.addView(search)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(Ui.scroll(this, results), LinearLayout.LayoutParams(-1, Ui.dp(this, 280)))
        val groups = serviceGroups()
        val dialog = AlertDialog.Builder(this).setTitle(if (includeAll) "Сервисы" else "Сервис").setView(panel).setNegativeButton("Закрыть", null).create()
        fun update() {
            results.removeAllViews()
            val terms = search.text.toString().trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val choices = (if (includeAll) listOf(null to "Все сервисы") else emptyList()) + groups.map { it.first.uuid to it.second }
            val matches = choices.filter { (_, name) -> terms.all { name.lowercase().contains(it) } }
            if (matches.isEmpty()) results.addView(Ui.label(this, "Сервисы не найдены", muted = true))
            matches.forEach { (id, name) ->
                results.addView(Ui.button(this, name) {
                    if (store.database != null) selected(id)
                    dialog.dismiss()
                }.apply {
                    contentDescription = "Выбрать сервис $name"
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    if (id == currentId) setTextColor(Ui.accent)
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { store.touch(); update() }
            override fun afterTextChanged(s: Editable?) {}
        })
        update(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show(); Ui.recordDialog(dialog); protect(dialog)
        search.focusKeyboard { dialog.isShowing && store.database != null }
    }
    private fun settingsUi(panel: LinearLayout) {
        fun section(title: String): LinearLayout {
            panel.addView(Ui.label(this, title, 13f, muted = true).apply {
                setPadding(Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8))
            })
            return Ui.card(this).apply { setPadding(0, 0, 0, 0); clipToOutline = true; panel.addView(this) }
        }
        val security = section("Открытие хранилища")
        val passwordRow = Ui.settingRow(this, R.drawable.ic_lock, "Использовать мастер-пароль")
        passwordRow.addView(Switch(this).apply {
            contentDescription = "Использовать мастер-пароль"
            isChecked = store.masterPasswordRequired; isEnabled = !store.busy
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.accent, Ui.muted))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(android.graphics.Color.rgb(58, 87, 110), android.graphics.Color.rgb(62, 68, 81)))
            minimumHeight = Ui.dp(this@MainActivity, 48)
            setOnCheckedChangeListener { _, required -> store.setMasterPasswordRequired(required) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(this@MainActivity, 8) })
        security.addView(passwordRow)
        val display = section("Поля записей")
        val secretRow = Ui.settingRow(this, R.drawable.ic_lock, "Скрывать пароли и ключи")
        secretRow.addView(Switch(this).apply {
            contentDescription = "Скрывать пароли и ключи"
            isChecked = store.hideRecordSecrets
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.accent, Ui.muted))
            minimumHeight = Ui.dp(this@MainActivity, 48)
            setOnCheckedChangeListener { _, hidden -> store.setHideRecordSecrets(hidden) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(this@MainActivity, 8) })
        display.addView(secretRow)
        val sync = section("Синхронизация")
        sync.addView(Ui.settingRow(this, R.drawable.ic_sync, "С компьютером", "Синхронизация с компьютером", store.syncSummary) { page = Page.SYNC; render() }.apply {
            syncFeedback = findViewWithTag("setting-subtitle")
        })
        val backup = section("Резервные копии")
        backup.addView(Ui.settingRow(this, R.drawable.ic_backup, "Сохранить копию", "Сохранить зашифрованную копию") { export(store.file) }.apply {
            isEnabled = store.file.exists() && !store.busy; alpha = if (isEnabled) 1f else .4f
        })
        if (!store.file.exists()) backup.addView(Ui.settingRow(this, R.drawable.ic_backup, "Открыть файл KDBX", "Открыть файл KDBX с компьютера") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 10)
        })
        val access = section("Быстрый доступ")
        access.addView(Ui.settingRow(this, R.drawable.ic_keyshelf_mark, "Плавающая кнопка", "Включить плавающую кнопку") {
            AlertDialog.Builder(this).setTitle("Быстрый доступ")
                .setMessage("В специальных возможностях выберите KeyShelf и включите службу. Она определяет активное поле и вставляет только выбранное вами значение. После установки APK Android может попросить разрешить ограниченные настройки в сведениях о приложении.")
                .setPositiveButton("Открыть настройки") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.setNegativeButton("Отмена", null).showStyled()
        })
        centerPageText(panel)
    }
    private fun centerPageText(view: android.view.View) {
        if (view is TextView && view !is CompoundButton) {
            view.gravity = android.view.Gravity.CENTER
            view.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) centerPageText(view.getChildAt(i))
    }
    private fun details(row: VaultRow) {
        val panel = Ui.panel(this)
        val heading = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply { setImageResource(R.drawable.ic_keyshelf) },
            LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)).apply { rightMargin = Ui.dp(this@MainActivity, 12) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(Ui.label(this, row.title, 21f).apply {
            setTextColor(Ui.typeColor(row.kind))
            setPadding(0, 0, 0, 0); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        names.addView(Ui.label(this, row.service, 13f, muted = true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, 0); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (row.kind == "api") names.addView(Ui.apiBadge(this, row.meta.optBoolean("api_paid"), full = true),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })
        heading.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 20) })
        row.displayFields.filter { it.value.isNotEmpty() }.forEach {
            if (it.label == "Секрет 2FA" && row.kind == "account") {
                panel.addView(Ui.button(this, "Копировать текущий код 2FA") {
                    runCatching { copy(Totp.code(it.value)) }.onFailure { message("Проверьте секрет 2FA") }
                })
            } else valueUi(panel, it.label, it.value, store.hideRecordSecrets && it.secret && row.kind != "other", showLabel = row.kind != "other")
        }
        row.entry.fields.notes?.content?.takeIf {
            it.isNotBlank() && !it.trim().equals(row.service.trim(), ignoreCase = true)
        }?.let { panel.addView(Ui.label(this, it)) }
        val dialog = AlertDialog.Builder(this).setView(Ui.scroll(this, panel)).setNegativeButton("Закрыть", null)
            .setPositiveButton("Изменить") { _, _ -> editor(row) }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        Ui.recordDialog(dialog)
        protect(dialog)
    }
    private fun valueUi(panel: LinearLayout, name: String, value: String, secret: Boolean, showLabel: Boolean = true) {
        val line = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.surface); cornerRadius = Ui.dp(this@MainActivity, 12).toFloat()
            }
        }
        val field = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (showLabel) field.addView(Ui.label(this, name, 12f, muted = true).apply { setPadding(0, 0, 0, Ui.dp(this@MainActivity, 4)) })
        val text = Ui.label(this, if (secret) "••••••••" else value).apply {
            setPadding(0, 0, 0, 0); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        field.addView(text)
        line.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        if (secret) {
            var revealed = false
            lateinit var reveal: ImageButton
            reveal = Ui.iconButton(this, R.drawable.ic_visibility, "Показать $name") {
                revealed = !revealed; text.text = if (revealed) value else "••••••••"
                reveal.setImageResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
                reveal.contentDescription = (if (revealed) "Скрыть " else "Показать ") + name
                reveal.tooltipText = reveal.contentDescription
            }
            line.addView(reveal)
        }
        line.addView(Ui.iconButton(this, R.drawable.ic_copy, "Копировать $name") { copy(value) })
        panel.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 10) })
    }
    private fun editor(old: VaultRow?) {
        if (store.database == null) return
        val serviceChoices = serviceGroups()
        val groups = serviceChoices.map { it.first }
        val panel = Ui.panel(this)
        val title = Ui.input(this, "Название", old?.title.orEmpty())
        val alias = Ui.input(this, "Метка", old?.alias.orEmpty())
        lateinit var group: android.widget.Spinner
        group = Ui.selector(this, serviceChoices.map { it.second }, "Сервис записи") {
            chooseService(includeAll = false, currentId = groups[group.selectedItemPosition].uuid) { id ->
                val index = groups.indexOfFirst { it.uuid == id }
                if (index >= 0) group.setSelection(index)
            }
        }.apply {
            minimumHeight = Ui.dp(this@MainActivity, 52)
            setSelection(groups.indexOfFirst { it.uuid == (old?.group?.uuid ?: selectedService) }.coerceAtLeast(0)) }
        panel.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) })
        val kinds = listOf("account", "api", "other", "legacy")
        val type = Ui.selector(this, listOf("Аккаунт", "API", "Другое", "Сохранённые поля"), "Тип записи").apply {
            minimumHeight = Ui.dp(this@MainActivity, 52)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) }
            setSelection(kinds.indexOf(old?.kind ?: "account").coerceAtLeast(0)) }; panel.addView(type)
        panel.addView(title)
        panel.addView(alias)
        val login = Ui.input(this, "Логин", old?.login.orEmpty()); panel.addView(login)
        var activeEdit: EditText? = null
        listOf(title, alias, login).forEach { edit -> edit.setOnFocusChangeListener { _, focused -> if (focused) activeEdit = edit } }
        val favorite = Ui.iconButton(this, R.drawable.ic_star_outline, "Избранное записи") {}
        Ui.styleFavorite(favorite, old?.favorite ?: false)
        favorite.setOnClickListener { Ui.styleFavorite(favorite, !favorite.isSelected) }
        val paid = CheckBox(this).apply {
            text = "Платный API"; setTextColor(Ui.text); isChecked = old?.meta?.optBoolean("api_paid") ?: false
            visibility = if (old?.kind == "api") android.view.View.VISIBLE else android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) }
        }; panel.addView(paid)
        val fieldsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; panel.addView(fieldsPanel)
        val edits = mutableListOf<Pair<VaultField, EditText>>()
        fun renumberExtras() {
            var number = 0
            edits.indices.forEach { i ->
                val (field, edit) = edits[i]
                if (field.label.startsWith("Дополнительное поле ")) {
                    val label = "Дополнительное поле ${++number}"
                    edits[i] = field.copy(label = label) to edit; edit.hint = label
                }
            }
        }
        fun add(f: VaultField, index: Int = edits.size, focus: Boolean = false) {
            val line = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val edit = Ui.input(this, f.label, f.value, store.hideRecordSecrets && f.secret && kinds[type.selectedItemPosition] != "other")
            edit.setOnFocusChangeListener { _, focused -> if (focused) activeEdit = edit }
            line.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(Ui.button(this, "−") { edits.removeAll { it.second === edit }; edit.text.clear(); fieldsPanel.removeView(line); renumberExtras() },
                LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginStart = Ui.dp(this@MainActivity, 8) })
            fieldsPanel.addView(line, index, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) }); edits.add(index, f to edit)
            renumberExtras()
            if (focus) edit.requestFocus()
        }
        var lastKind = old?.kind ?: "account"
        fun hasLogin(kind: String) = kind == "account" || kind == "legacy"
        login.visibility = if (hasLogin(lastKind)) android.view.View.VISIBLE else android.view.View.GONE
        (old?.displayFields?.filter { it.key != "UserName" || !hasLogin(lastKind) }?.map {
            if (it.key == "UserName") it.copy(key = "", secret = true) else it
        }
            ?: listOf(VaultField("", "Пароль", "", true), VaultField("", "Секрет 2FA", "", true))).forEach { add(it) }
        if (!hasLogin(lastKind)) login.text.clear()
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val selected = kinds[pos]
                paid.visibility = if (selected == "api") android.view.View.VISIBLE else android.view.View.GONE
                login.visibility = if (hasLogin(selected)) android.view.View.VISIBLE else android.view.View.GONE
                if (selected == lastKind) return
                val values = (if (hasLogin(lastKind)) listOf(login.text.toString()) else emptyList()) + edits.map { it.second.text.toString() }
                val labels = when (selected) { "api" -> listOf("API-ключ"); "account" -> listOf("Пароль", "Секрет 2FA"); "other" -> listOf("Поле 1", "Поле 2", "Поле 3"); else -> edits.map { it.first.label } }
                login.setText(if (hasLogin(selected)) values.firstOrNull().orEmpty() else "")
                val fieldValues = if (hasLogin(selected)) values.drop(1) else values
                edits.forEach { it.second.text.clear() }
                activeEdit = null
                edits.clear(); fieldsPanel.removeAllViews()
                for (i in 0 until maxOf(labels.size, fieldValues.size)) add(VaultField("", labels.getOrElse(i) { "Дополнительное поле ${i - labels.size + 1}" }, fieldValues.getOrElse(i) { "" }, true))
                lastKind = selected
            }
        }
        val fieldActions = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; contentDescription = "Действия полей записи" }
        fieldActions.addView(Ui.button(this, "+ Поле") {
            val next = edits.count { it.first.label.startsWith("Дополнительное поле ") } + 1
            val active = edits.indexOfFirst { it.second === activeEdit }
            val index = if (active >= 0) active + 1 else if (activeEdit in listOf(title, alias, login)) 0 else edits.size
            add(VaultField("", "Дополнительное поле $next", "", true), index, focus = true)
        }.apply { contentDescription = "Добавить поле" }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply { marginEnd = Ui.dp(this@MainActivity, 4) })
        fieldActions.addView(favorite, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply { marginStart = Ui.dp(this@MainActivity, 4) })
        panel.addView(fieldActions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) })
        val notes = Ui.input(this, "Заметка", old?.entry?.fields?.notes?.content.orEmpty()).apply {
            setSingleLine(false); minLines = 2
            setOnFocusChangeListener { _, focused -> if (focused) activeEdit = this }
        }; panel.addView(notes)
        val dialog = AlertDialog.Builder(this).setTitle(if (old == null) "Новая запись" else "Изменить запись").setView(Ui.scroll(this, panel))
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null)
        if (old != null) dialog.setNeutralButton("Удалить") { _, _ ->
            AlertDialog.Builder(this).setMessage("Удалить запись «${old.title}»?").setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ ->
                    pendingDeletedRecord = old.id to visibleRecordIds.toList()
                    store.mutate { it.removeEntry(old.entry.uuid) }
                }.showStyled()
        }
        val shown = dialog.create(); shown.show(); Ui.recordDialog(shown); protect(shown)
        title.focusKeyboard { shown.isShowing && store.database != null }
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = edits.map { (f, e) -> f.copy(value = e.text.toString()) }
            if (title.text.isBlank()) { title.error = "Укажите название"; return@setOnClickListener }
            val chosen = kinds[type.selectedItemPosition]
            if (chosen == "account" && values.any { it.label == "Секрет 2FA" && it.value.isNotEmpty() && runCatching { Totp.code(it.value) }.isFailure }) {
                message("Неверный секрет 2FA"); return@setOnClickListener
            }
            val savedTitle = title.text.toString(); val savedLogin = login.text.toString(); val savedNotes = notes.text.toString()
            val savedAlias = alias.text.toString()
            val savedGroup = groups[group.selectedItemPosition].uuid; val savedFavorite = favorite.isSelected; val savedPaid = chosen == "api" && paid.isChecked
            store.mutate { VaultCodec.saveRow(it, old, savedGroup, savedTitle, savedLogin, savedNotes, chosen, savedFavorite, values, savedPaid, alias = savedAlias) }
            shown.dismiss()
        }
    }
    private fun newService() {
        val name = Ui.input(this, "Название сервиса")
        val dialog = AlertDialog.Builder(this).setTitle("Новый сервис").setView(name).setNegativeButton("Отмена", null).setPositiveButton("Добавить") { _, _ ->
            val value = name.text.toString().trim()
            if (value.isNotBlank()) store.mutate { db -> db.modifyGroup(db.content.group.uuid) { copy(groups = groups + Group(UUID.randomUUID(), value)) } }
        }.showStyled()
        protect(dialog); name.focusKeyboard { dialog.isShowing && store.database != null }
    }
    private fun syncUi(panel: LinearLayout) {
        val paired = SyncClient.isPaired(this)
        fun section(title: String): LinearLayout {
            panel.addView(Ui.label(this, title, 13f, muted = true).apply {
                setPadding(Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8))
            })
            return Ui.card(this).apply { panel.addView(this) }
        }
        fun action(card: LinearLayout, title: String, primary: Boolean = false, danger: Boolean = false,
                   enabled: Boolean = !store.busy, click: () -> Unit) {
            card.addView(Ui.button(this, title, click).apply {
                Ui.dialogAction(this, primary, danger)
                isEnabled = enabled; alpha = if (enabled) 1f else .4f
                syncButtons.add(this to { !store.busy && (title != "Проверить входящие пакеты" || store.database != null) })
            }, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)))
        }
        val status = Ui.card(this).apply { setPadding(0, 0, 0, 0); panel.addView(this) }
        status.addView(Ui.settingRow(this, R.drawable.ic_sync,
            if (paired) "Сопряжение сохранено" else "Подключите компьютер",
            subtitle = "Wi-Fi · Одна локальная сеть"))
        status.addView(Ui.label(this, store.syncSummary, 13f, muted = true).apply {
            syncFeedback = this
            setPadding(Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 14))
        })
        val connection = section("Подключение")
        action(connection, "Сканировать QR", primary = !paired) {
            startActivityForResult(Intent(this, QrActivity::class.java), 12)
        }
        connection.addView(Ui.settingRow(this, R.drawable.ic_keyshelf_mark, "Как подключить компьютер") {
            AlertDialog.Builder(this).setTitle("Подключение по Wi-Fi")
                .setMessage("Подключите телефон и компьютер к одной Wi-Fi сети. На компьютере: KeyShelf → Настройки → Связать телефон по Wi-Fi. Нажмите «Сканировать QR» на телефоне и наведите камеру на код.\n\nОбмен работает, пока оба приложения доступны в локальной сети.")
                .setPositiveButton("Понятно", null).showStyled()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })
        if (paired) {
            val exchange = section("Хранилище")
            if (!store.file.exists()) action(exchange, "Получить хранилище", primary = true) {
                passwordDialog { pass -> store.download(pass) }
            } else action(exchange, "Проверить входящие пакеты", primary = true, enabled = !store.busy && store.database != null) {
                store.sync()
            }
            if (store.file.exists() && store.database == null) exchange.addView(Ui.label(this, "Откройте хранилище для синхронизации", 13f, muted = true))
        }
        store.pendingSync?.let { proposal ->
            val review = section("Ожидающие изменения")
            action(review, "Ответить на запрос синхронизации", primary = true) {
                maybeShowSyncReview(force = true)
            }
        }
        if (paired) {
            val unlink = section("Связь с компьютером")
            action(unlink, "Удалить связь", danger = true) { store.clearPairing() }
        }
        centerPageText(panel)
    }
    private fun passwordDialog(action: (String) -> Unit) {
        val edit = Ui.input(this, "Мастер-пароль этой базы", secret = true)
        val dialog = AlertDialog.Builder(this).setTitle("Открыть KDBX").setView(edit).setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть") { _, _ -> val pass = edit.text.toString(); edit.text.clear(); action(pass) }.create()
        dialog.show(); Ui.recordDialog(dialog); protect(dialog)
        edit.focusKeyboard { dialog.isShowing }
    }
    private fun export(file: File) {
        exportFile = file
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, file.name); addCategory(Intent.CATEGORY_OPENABLE) }, 11)
    }
    @Deprecated("Uses legacy scanner/document activity result API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        if (request == 12) { data?.getStringExtra("invitation")?.let { payload -> waitingForPair = true; page = Page.SYNC; store.pair(payload) }; return }
        val uri = data?.data ?: return
        if (request == 10) passwordDialog { pass -> store.job({
            contentResolver.openInputStream(uri)!!.use { stream ->
                val bytes = stream.readBytesBounded(32 * 1024 * 1024); VaultCodec.decode(bytes, pass); bytes }
        }) { store.importFile(it as ByteArray, pass) } }
        if (request == 11) { val file = exportFile ?: return; store.job({ contentResolver.openOutputStream(uri, "wt")!!.use { it.write(file.readBytes()) } }) { message("Зашифрованная копия сохранена") } }
    }
    private fun copy(value: String) {
        val clip = ClipData.newPlainText("KeyShelf", value)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(clip); message("Скопировано")
        Handler(Looper.getMainLooper()).postDelayed({
            if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == value) clipboard.clearPrimaryClip()
        }, 30000)
    }
    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun protect(dialog: android.app.Dialog, onDismiss: (() -> Unit)? = null) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sensitiveDialogs.add(dialog)
        dialog.setOnDismissListener {
            dialog.window?.decorView?.let(::scrub)
            sensitiveDialogs.remove(dialog)
            onDismiss?.invoke()
        }
    }
    private fun scrub(view: android.view.View) {
        if (view is EditText) view.text.clear()
        else if (view is TextView) view.text = ""
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) scrub(view.getChildAt(i))
    }
    private fun closeSensitiveDialogs() {
        sensitiveDialogs.toList().forEach { dialog -> dialog.window?.decorView?.let(::scrub); dialog.dismiss() }
        sensitiveDialogs.clear()
    }
}

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit); output.write(buffer, 0, count) }
    return output.toByteArray()
}
