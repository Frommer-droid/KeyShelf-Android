package app.keyshelf

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.os.Looper
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = KeyShelfApp::class, shadows = [WindowsAtomicFileShadow::class])
class QuickSearchTest {
    @Test fun secretDisplayPreferenceMasksButInsertsOriginalAndSurvivesReopen() {
        val (app, store) = populated()
        val row = VaultCodec.rows(store.database!!).first { it.title == "Рабочая почта" }
        var inserted = ""
        val panel = QuickSearchPanel(app, store, { _, value -> inserted = value }, { _, _ -> }, {})
        panel.search.setText("Рабочая")
        find(panel, "Раскрыть запись KeyShelf · Рабочая почта")!!.performClick()
        for (hidden in listOf(true, false, true)) {
            store.setHideRecordSecrets(hidden)
            assertEquals(hidden, app.getSharedPreferences("display", 0).getBoolean("hide_record_secrets", !hidden))
            val display = find(panel, "Пароль") as TextView
            assertEquals(if (hidden) "••••••••" else "synthetic-secret-Рабочая почта", display.text.toString())
            display.performClick()
            assertEquals("synthetic-secret-Рабочая почта", inserted)
            val form = MiniFieldForm(app, store, row, "Пароль") {}
            assertEquals(hidden, form.initialFocus.transformationMethod is android.text.method.PasswordTransformationMethod)
            assertEquals("synthetic-secret-Рабочая почта", form.initialFocus.text.toString())
            form.dispose()
        }
        assertTrue(row.fields.first { it.label == "Пароль" }.secret)
        panel.dispose()
    }
    private fun wait(store: VaultStore) {
        val end = System.currentTimeMillis() + 20000
        while (store.busy && System.currentTimeMillis() < end) {
            Thread.sleep(30); shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
        }
        assertFalse(store.busy); assertEquals("", store.error)
    }
    private fun populated(): Pair<KeyShelfApp, VaultStore> {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val store = app.store
        store.setHideRecordSecrets(false) // Existing scenarios verify full visible values.
        store.create("synthetic-master-123"); wait(store)
        for (title in listOf("Рабочая почта", "Личная почта")) {
            store.mutate { db -> VaultCodec.saveRow(db, null, db.content.group.uuid, title, "synthetic-login", "", "account", false,
                listOf(VaultField("", "Пароль", "synthetic-secret-$title", true), VaultField("", "Секрет 2FA", "", true))) }
            wait(store)
            assertTrue(VaultCodec.rows(VaultCodec.decode(store.file.readBytes(), "synthetic-master-123")).any { it.title == title })
        }
        return app to store
    }
    private fun visibleTexts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val text = if (view is TextView) listOf(view.text.toString()) else emptyList()
        return text + if (view is ViewGroup) (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) } else emptyList()
    }
    private fun find(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), description)?.let { return it }
        return null
    }
    @Test fun oldOtherUsernameExpandsAsOrdinaryFieldAndCanBeInsertedAndEdited() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val store = app.store
        store.create("synthetic-master-123"); wait(store)
        store.mutate { db -> VaultCodec.saveRow(db, null, db.content.group.uuid,
            "Old other", "synthetic-ordinary-value", "", "other", false, emptyList(), alias = ".old") }
        wait(store)
        val row = VaultCodec.rows(store.database!!).single()
        assertEquals(0, row.meta.getJSONArray("field_layout").length())
        var pasted = ""; var edited = ""
        val panel = QuickSearchPanel(app, store, { _, value -> pasted = value }, { _, label -> edited = label }, {})
        panel.search.setText(".old")
        val collapsedHeight = panel.resultsScroll.layoutParams.height
        find(panel, "Раскрыть запись KeyShelf · Old other")!!.performClick()
        assertNotNull(find(panel, "Свернуть запись KeyShelf · Old other"))
        assertNull(find(panel, "Логин"))
        assertTrue(visibleTexts(panel).contains("synthetic-ordinary-value"))
        assertTrue(panel.resultsScroll.layoutParams.height > collapsedHeight)
        find(panel, "Поле 1")!!.performClick(); assertEquals("synthetic-ordinary-value", pasted)
        find(panel, "Редактировать Поле 1")!!.performClick(); assertEquals("Поле 1", edited)
        val form = MiniFieldForm(app, store, row, "Поле 1") {}
        assertFalse(form.initialFocus.transformationMethod is android.text.method.PasswordTransformationMethod)
        form.initialFocus.setText("synthetic-updated-value")
        val actions = form.getChildAt(form.childCount - 1) as ViewGroup
        actions.getChildAt(1).performClick(); wait(store)
        val updated = VaultCodec.rows(store.database!!).single()
        assertEquals("synthetic-updated-value", updated.login)
        assertEquals(".old", updated.alias)
        assertEquals(0, updated.meta.getJSONArray("field_layout").length())
        panel.dispose(); form.dispose()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        find(controller.get().window.decorView, "Открыть запись Old other")!!.performClick()
        val details = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertFalse(visibleTexts(details.window!!.decorView).contains("Логин"))
        assertFalse(visibleTexts(details.window!!.decorView).contains("Поле 1"))
        assertTrue(visibleTexts(details.window!!.decorView).contains("synthetic-updated-value"))
        details.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val editor = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        fun input(view: View): android.widget.EditText? {
            if (view is android.widget.EditText && view.hint.toString() == "Поле 1") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
            return null
        }
        val ordinary = input(editor.window!!.decorView)!!
        assertFalse(ordinary.transformationMethod is android.text.method.PasswordTransformationMethod)
        assertEquals("synthetic-updated-value", ordinary.text.toString())
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
        val migrated = VaultCodec.rows(VaultCodec.decode(store.file.readBytes(), "synthetic-master-123")).single()
        assertEquals("", migrated.login)
        assertEquals(".old", migrated.alias)
        assertEquals("synthetic-updated-value", migrated.displayFields.single().value)
        assertTrue(migrated.displayFields.single().secret)
        controller.pause().stop().destroy()
    }
    @Test fun blankQueryCollapsedResultsAndOneExpandedRecord() {
        val (app, store) = populated()
        var edited = ""; var inserted = ""; var closed = false
        val panel = QuickSearchPanel(app, store, { _, value -> inserted = value }, { row, field -> edited = row.title + ":" + field }, { closed = true })
        val header = panel.getChildAt(0) as ViewGroup
        assertEquals(listOf("Очистить поиск", "Избранное", "Закрыть поиск"),
            (1 until header.childCount).map { header.getChildAt(it).contentDescription.toString() })
        assertEquals(View.GONE, panel.resultsScroll.visibility)
        panel.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(app, 360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Ui.dp(app, 640), View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertEquals(Ui.dp(app, 52), panel.measuredHeight)
        for (i in 1 until header.childCount) {
            assertEquals(Ui.dp(app, 48), header.getChildAt(i).width)
            assertEquals(header.getChildAt(1).height, header.getChildAt(i).height)
        }
        assertFalse(visibleTexts(panel).any { it.contains("почта") })
        panel.search.setText("почта")
        assertEquals(2, panel.results.childCount)
        assertFalse(visibleTexts(panel).any { it.contains("synthetic-secret") })
        find(panel, "Раскрыть запись KeyShelf · Рабочая почта")!!.performClick()
        assertTrue(visibleTexts(panel).contains("synthetic-secret-Рабочая почта"))
        find(panel, "Редактировать Пароль")!!.performClick()
        assertEquals("Рабочая почта:Пароль", edited)
        assertEquals("", inserted)
        assertNull(find(panel, "Копировать Пароль"))
        val valueDisplay = find(panel, "Пароль") as TextView
        assertEquals(1, valueDisplay.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, valueDisplay.ellipsize)
        assertNull(find(panel, "Вставить Пароль"))
        find(panel, "Пароль")!!.performClick()
        assertEquals("synthetic-secret-Рабочая почта", inserted)
        find(panel, "Логин")!!.performClick()
        assertEquals("synthetic-login", inserted)
        val oldDisplay = find(panel, "Пароль") as TextView
        find(panel, "Раскрыть запись KeyShelf · Личная почта")!!.performClick()
        assertEquals("", oldDisplay.text.toString())
        assertFalse(visibleTexts(panel).contains("synthetic-secret-Рабочая почта"))
        assertTrue(visibleTexts(panel).contains("synthetic-secret-Личная почта"))
        find(panel, "Очистить поиск")!!.performClick()
        assertEquals(View.GONE, panel.resultsScroll.visibility)
        assertEquals(0, panel.results.childCount)
        panel.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        assertTrue(closed)
        panel.dispose()
    }
    @Test fun refreshKeepsQueryAndLockRemovesSecrets() {
        val (app, store) = populated()
        val panel = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {})
        panel.search.setText("Рабочая"); panel.search.requestFocus()
        find(panel, "Раскрыть запись KeyShelf · Рабочая почта")!!.performClick()
        panel.refresh()
        assertEquals("Рабочая", panel.search.text.toString())
        assertTrue(panel.search.isFocused)
        assertTrue(visibleTexts(panel).contains("synthetic-secret-Рабочая почта"))
        val displayed = find(panel, "Пароль") as TextView
        store.lock()
        assertEquals("", displayed.text.toString())
        assertEquals("", panel.search.text.toString())
        assertEquals(View.GONE, panel.resultsScroll.visibility)
        assertFalse(visibleTexts(panel).any { it.contains("synthetic-secret") })
        assertTrue(visibleTexts(panel).contains("Разблокировать"))
        panel.dispose()
    }
    @Test fun syncNotificationsKeepExpandedValuesAndSelection() {
        val (app, store) = populated()
        val panel = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {})
        panel.search.setText("Рабочая"); panel.search.requestFocus(); panel.search.setSelection(3)
        find(panel, "Раскрыть запись KeyShelf · Рабочая почта")!!.performClick()
        val value = find(panel, "Пароль")!!
        store.job({ Thread.sleep(60) }, syncOperation = true)
        assertSame(value, find(panel, "Пароль"))
        wait(store)
        assertSame(value, find(panel, "Пароль"))
        assertTrue(panel.search.isFocused); assertEquals(3, panel.search.selectionStart)
        store.lock()
        assertEquals("", (value as TextView).text.toString())
        assertEquals(View.GONE, panel.resultsScroll.visibility)
        panel.dispose()
    }
    @Test fun autofillOpensOnlyQuickActivityAndLauncherKeepsFullScreen() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        assertEquals(QuickFillActivity::class.java.name, FillService.authenticationIntent(app).component!!.className)
        val mini = Robolectric.buildActivity(QuickFillActivity::class.java).setup()
        val texts = visibleTexts(mini.get().window.decorView)
        assertFalse(texts.contains("Синхронизация с компьютером"))
        assertFalse(texts.contains("+ Запись"))
        mini.pause().stop().destroy()
        val full = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(find(full.get().window.decorView, "Меню"))
        assertTrue(visibleTexts(full.get().window.decorView).contains("Создать хранилище"))
        full.pause().stop().destroy()
    }
    @Test fun reopenAfterInsertRestoresQueryAndExpandedRecordButScrubsOldView() {
        val (app, store) = populated()
        val state = store.quickSearchState
        var inserted = ""
        lateinit var first: QuickSearchPanel
        first = QuickSearchPanel(app, store, { _, value -> inserted = value; first.dispose() }, { _, _ -> }, {}, state = state)
        first.search.setText("Рабочая")
        find(first, "Раскрыть запись KeyShelf · Рабочая почта")!!.performClick()
        val oldValue = find(first, "Пароль") as TextView
        find(first, "Пароль")!!.performClick()
        assertEquals("synthetic-secret-Рабочая почта", inserted)
        assertEquals("", oldValue.text.toString())
        store.mutate { db ->
            val row = VaultCodec.rows(db).first { it.title == "Рабочая почта" }
            VaultCodec.saveRow(db, row, row.group.uuid, row.title, row.login, "", row.kind, row.favorite,
                row.fields.map { if (it.label == "Пароль") it.copy(value = "synthetic-current-secret") else it })
        }
        wait(store)
        val second = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {}, state = state)
        assertEquals("Рабочая", second.search.text.toString())
        assertNotNull(find(second, "Свернуть запись KeyShelf · Рабочая почта"))
        assertTrue(visibleTexts(second).contains("synthetic-current-secret"))
        assertFalse(visibleTexts(second).contains("synthetic-secret-Рабочая почта"))
        second.dispose()
        store.lock()
        assertEquals("", state.query); assertNull(state.expandedId); assertEquals(0, state.scrollY)
        store.unlock("synthetic-master-123"); wait(store)
        val third = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {}, state = state)
        assertEquals("", third.search.text.toString())
        assertEquals(View.GONE, third.resultsScroll.visibility)
        third.dispose()
    }
    @Test fun ellipsisDoesNotTruncateInsertedValue() {
        val (app, store) = populated()
        val fullValue = "synthetic-long-" + "x".repeat(240)
        store.mutate { db ->
            val row = VaultCodec.rows(db).first { it.title == "Рабочая почта" }
            VaultCodec.saveRow(db, row, row.group.uuid, row.title, row.login, "", row.kind, row.favorite,
                row.fields.map { if (it.label == "Пароль") it.copy(value = fullValue) else it })
        }
        wait(store)
        var inserted = ""
        val panel = QuickSearchPanel(app, store, { _, value -> inserted = value }, { _, _ -> }, {})
        panel.search.setText("Рабочая")
        find(panel, "Раскрыть запись KeyShelf · Рабочая почта")!!.performClick()
        val display = find(panel, "Пароль") as TextView
        assertEquals(1, display.maxLines); assertEquals(android.text.TextUtils.TruncateAt.END, display.ellipsize)
        display.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.AT_MOST))
        display.layout(0, 0, 100, display.measuredHeight)
        assertEquals(1, display.layout.lineCount)
        find(panel, "Пароль")!!.performClick()
        assertEquals(fullValue, inserted)
        panel.dispose()
        inserted = ""; display.performClick(); assertEquals("", inserted)
    }
    @Test fun favoritesShowWithoutQueryFilterSearchAndRestoreOnReopen() {
        val (app, store) = populated()
        store.mutate { db ->
            val row = VaultCodec.rows(db).first { it.title == "Рабочая почта" }
            VaultCodec.saveRow(db, row, row.group.uuid, row.title, row.login, "", row.kind, true, row.fields)
        }
        wait(store)
        val panel = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {}, state = store.quickSearchState)
        assertEquals(View.GONE, panel.resultsScroll.visibility)
        val star = find(panel, "Избранное")!!
        star.performClick()
        assertTrue(star.isSelected)
        assertEquals(View.VISIBLE, panel.resultsScroll.visibility)
        assertEquals(1, panel.results.childCount)
        assertNotNull(find(panel, "Раскрыть запись KeyShelf · Рабочая почта"))
        assertNull(find(panel, "Раскрыть запись KeyShelf · Личная почта"))
        panel.search.setText("Личная")
        assertNull(find(panel, "Раскрыть запись KeyShelf · Личная почта"))
        find(panel, "Очистить поиск")!!.performClick()
        find(panel, "Раскрыть запись KeyShelf · Рабочая почта")!!.performClick()
        panel.dispose()
        val restored = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {}, state = store.quickSearchState)
        assertTrue(find(restored, "Избранное")!!.isSelected)
        assertNotNull(find(restored, "Свернуть запись KeyShelf · Рабочая почта"))
        find(restored, "Избранное")!!.performClick()
        assertEquals(View.GONE, restored.resultsScroll.visibility)
        restored.dispose(); store.lock()
        assertFalse(store.quickSearchState.favoritesOnly)
    }
}
