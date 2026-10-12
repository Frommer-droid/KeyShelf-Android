package app.keyshelf

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.database.modifiers.removeEntry
import app.keemobile.kotpass.models.Group
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowPopupMenu
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = KeyShelfApp::class, shadows = [WindowsAtomicFileShadow::class])
class FullNavigationTest {
    @Test fun deletingRecordsKeepsScrollAcrossBusyAndCompletionButSearchResetsIt() {
        val (store, _) = populated()
        store.mutate { db ->
            var updated = db
            for (i in 0 until 70) updated = VaultCodec.saveRow(updated, null, db.content.group.uuid,
                "Scroll %02d".format(i), "", "", "other", false,
                listOf(VaultField("", "Поле 1", "synthetic-value", true)))
            updated
        }; wait(store)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        fun root() = controller.get().window.decorView
        fun layout() {
            val view = root()
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1400); idle()
        }
        fun scroll() = control(root(), "Список записей") as ScrollView
        layout()
        scroll().scrollTo(0, 2500)
        val before = scroll().scrollY
        assertTrue(before > 0)
        val removed = VaultCodec.rows(store.database!!).single { it.title == "Scroll 35" }
        // Busy and completion can both render before the replacement view gets
        // its first layout (for example while the delete confirmation closes).
        fun deleteFromUi(title: String, accept: Boolean = true) {
            control(root(), "Открыть запись $title").performClick(); idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(if (accept) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick(); idle(); wait(store)
        }
        deleteFromUi(removed.title)
        layout()
        assertNull(find(root()) { it.contentDescription?.toString() == "Открыть запись Scroll 35" })
        assertTrue(control(root(), "Открыть запись Scroll 36").isSelected)
        assertEquals(before, scroll().scrollY)
        assertNull(find(root()) { it.contentDescription?.toString() == "Открыть запись Scroll 35" })
        scroll().fullScroll(View.FOCUS_DOWN); idle()
        val bottom = scroll().scrollY
        val last = VaultCodec.rows(store.database!!).single { it.title == "Scroll 69" }
        deleteFromUi(last.title); layout()
        assertTrue(control(root(), "Открыть запись Scroll 68").isSelected)
        val maxY = (scroll().getChildAt(0).height - scroll().height + scroll().paddingTop + scroll().paddingBottom).coerceAtLeast(0)
        assertEquals(minOf(bottom, maxY), scroll().scrollY)
        val search = find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText
        search.setText("Scroll 00"); layout()
        deleteFromUi("Scroll 00", accept = false); layout()
        assertNotNull(find(root()) { it.contentDescription?.toString() == "Открыть запись Scroll 00" })
        deleteFromUi("Scroll 00"); layout()
        assertNull(find(root()) { it.contentDescription?.toString()?.startsWith("Открыть запись") == true })
        assertTrue(texts(root()).contains("Записей: 0"))
        (find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText).setText("Scroll"); layout()
        assertEquals(0, scroll().scrollY)
        store.lock(); idle(); layout()
        store.unlock("synthetic-master-123"); wait(store); layout()
        assertEquals(0, scroll().scrollY)
        controller.pause().stop().destroy()
    }
    @Test fun allEditorKindsUseUniformSpacingEqualActionsAndInsertAfterCursor() {
        val (store, _) = populated()
        for (kind in listOf("account", "api", "other", "legacy")) {
            val fields = when (kind) {
                "account" -> listOf("Пароль" to "first", "Секрет 2FA" to "", "Дополнительное поле 1" to "tail")
                "api" -> listOf("API-ключ" to "first", "Дополнительное поле 1" to "tail")
                "other" -> listOf("Поле 1" to "first", "Поле 2" to "second", "Поле 3" to "tail")
                else -> listOf("Custom first" to "first", "Custom second" to "tail")
            }
            store.mutate { db -> VaultCodec.saveRow(db, null, db.content.group.groups.first().uuid,
                "Editor $kind", if (kind in listOf("account", "legacy")) "synthetic-login" else "", "", kind, false,
                fields.map { VaultField("", it.first, it.second, true) }) }; wait(store)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
            control(controller.get().window.decorView, "Открыть запись Editor $kind").performClick(); idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            val editor = ShadowAlertDialog.getLatestAlertDialog()
            val root = editor.window!!.decorView
            val group = control(root, "Сервис записи") as Spinner
            val type = control(root, "Тип записи") as Spinner
            assertEquals(0, group.paddingRight); assertEquals(0, type.paddingRight)
            val panel = group.parent as LinearLayout
            val first = find(root) { it is EditText && it.hint.toString() == fields.first().first } as EditText
            val rows = first.parent.parent as LinearLayout
            fun measure() {
                panel.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(controller.get(), 360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            }
            measure()
            assertEquals(Ui.dp(controller.get(), 8), type.top - group.bottom)
            assertSame(group, panel.getChildAt(0))
            assertSame(type, panel.getChildAt(1))
            val title = find(root) { it is EditText && it.hint.toString() == "Название" }!!
            assertEquals(Ui.dp(controller.get(), 8), title.top - type.bottom)
            val star = control(root, "Избранное записи") as ImageButton
            val add = control(root, "Добавить поле") as Button
            assertSame(add.parent, star.parent)
            assertEquals(add.width, star.width); assertEquals(add.height, star.height)
            assertNull(find(root) { it is CheckBox && it.text.toString() == "Избранное" })
            assertFalse(star.isSelected)
            star.performClick(); assertTrue(star.isSelected)
            assertEquals(Ui.favorite, star.imageTintList!!.defaultColor)
            star.performClick(); assertFalse(star.isSelected)
            first.requestFocus(); idle(); add.performClick(); idle()
            val inserted = (rows.getChildAt(1) as LinearLayout).getChildAt(0) as EditText
            assertTrue(inserted.hint.toString().startsWith("Дополнительное поле "))
            assertTrue(inserted.isFocused)
            inserted.setText("inserted-middle")
            measure()
            for (i in 1 until rows.childCount) assertEquals(Ui.dp(controller.get(), 8), rows.getChildAt(i).top - rows.getChildAt(i - 1).bottom)
            star.performClick()
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
            val saved = VaultCodec.rows(store.database!!).single { it.title == "Editor $kind" }
            assertTrue(saved.favorite)
            assertEquals("first", saved.fields[0].value)
            assertEquals("inserted-middle", saved.fields[1].value)
            assertEquals(fields.drop(1).map { it.second }, saved.fields.drop(2).map { it.value })
            controller.pause().stop().destroy()
        }
        store.lock(); idle(); store.unlock("synthetic-master-123"); wait(store)
        assertEquals(4, VaultCodec.rows(store.database!!).count { it.title.startsWith("Editor ") && it.favorite && it.fields[1].value == "inserted-middle" })
    }
    @Test fun editorServiceSearchMovesOnlyOnSaveAndRefreshesBothCounts() {
        val (store, destinationId) = populated()
        val nested = Group(UUID.randomUUID(), "Вложенный")
        store.mutate { db ->
            db.modifyGroup(db.content.group.uuid) { copy(name = "Root") }
                .modifyGroup(destinationId) { copy(groups = groups + nested) }
        }; wait(store)
        val before = VaultCodec.rows(store.database!!).single { it.title == "Первая запись" }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        fun root() = controller.get().window.decorView
        fun edit(): AlertDialog {
            control(root(), "Открыть запись Первая запись").performClick(); idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            return ShadowAlertDialog.getLatestAlertDialog()
        }
        fun search(editor: AlertDialog, query: String): AlertDialog {
            control(editor.window!!.decorView, "Сервис записи").performClick(); idle()
            val picker = ShadowAlertDialog.getLatestAlertDialog()
            assertFalse(texts(picker.window!!.decorView).contains("Все сервисы"))
            (control(picker.window!!.decorView, "Поиск названия сервиса") as EditText).setText(query); idle()
            return picker
        }
        (control(root(), "Выбрать сервис") as Spinner).setSelection(2); idle()
        val canceled = edit()
        assertNotNull(find(canceled.window!!.decorView) { it.contentDescription?.toString() == "Сервис записи" })
        val initial = control(canceled.window!!.decorView, "Сервис записи") as Spinner
        val recordType = control(canceled.window!!.decorView, "Тип записи")
        assertSame(initial.parent, recordType.parent)
        assertEquals(-1, initial.layoutParams.width)
        assertEquals(initial.layoutParams.width, recordType.layoutParams.width)
        assertNull(find(canceled.window!!.decorView) { it.contentDescription?.toString() == "Найти сервис записи" })
        assertEquals(1, initial.selectedItemPosition)
        var picker = search(canceled, "несуществующий сервис")
        assertTrue(texts(picker.window!!.decorView).contains("Сервисы не найдены"))
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertEquals(1, initial.selectedItemPosition)
        picker = search(canceled, "  ВЛОЖЕННЫЙ почта  ")
        control(picker.window!!.decorView, "Выбрать сервис Почта / Вложенный").performClick(); idle()
        assertEquals("Почта / Вложенный", initial.selectedItem)
        assertEquals(before.group.uuid, VaultCodec.rows(store.database!!).single { it.entry.uuid == before.entry.uuid }.group.uuid)
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertTrue(texts(root()).contains("Записей: 1"))
        val editor = edit()
        val selector = control(editor.window!!.decorView, "Сервис записи") as Spinner
        // Two equal names remain separate UUID destinations in the list.
        assertEquals(selector.getItemAtPosition(1), selector.getItemAtPosition(2))
        selector.setSelection(2); idle()
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
        assertTrue(texts(root()).contains("Записей: 0"))
        assertNull(find(root()) { it.contentDescription?.toString() == "Открыть запись Первая запись" })
        (control(root(), "Выбрать сервис") as Spinner).setSelection(3); idle()
        assertTrue(texts(root()).contains("Записей: 2"))
        assertNotNull(find(root()) { it.contentDescription?.toString() == "Открыть запись Первая запись" })
        val moved = VaultCodec.rows(store.database!!).single { it.entry.uuid == before.entry.uuid }
        assertEquals(destinationId, moved.group.uuid)
        assertEquals(before.login, moved.login)
        fun fieldValues(row: VaultRow) = row.fields.map { Triple(it.label, it.value, it.secret) }
        assertEquals(fieldValues(before), fieldValues(moved))
        assertEquals(before.fields.first { it.value.isNotEmpty() }.key, moved.fields.first { it.value.isNotEmpty() }.key)
        assertEquals(before.alias, moved.alias); assertEquals(before.favorite, moved.favorite)
        // Search selection persists as well, including nested groups and the root destination.
        val nestedEditor = edit()
        picker = search(nestedEditor, "Вложенный")
        control(picker.window!!.decorView, "Выбрать сервис Почта / Вложенный").performClick(); idle()
        nestedEditor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
        assertEquals(nested.uuid, VaultCodec.rows(store.database!!).single { it.entry.uuid == before.entry.uuid }.group.uuid)
        (control(root(), "Выбрать сервис") as Spinner).setSelection(4); idle()
        assertTrue(texts(root()).contains("Записей: 1"))
        val rootEditor = edit()
        picker = search(rootEditor, "Без сервиса")
        control(picker.window!!.decorView, "Выбрать сервис Без сервиса").performClick(); idle()
        rootEditor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
        store.lock(); idle(); store.unlock("synthetic-master-123"); wait(store)
        val reloaded = VaultCodec.rows(store.database!!).single { it.entry.uuid == before.entry.uuid }
        assertEquals(store.database!!.content.group.uuid, reloaded.group.uuid)
        assertEquals(fieldValues(before), fieldValues(reloaded)); assertEquals(2, VaultCodec.rows(store.database!!).size)
        controller.pause().stop().destroy()
    }
    @Test fun pairedStateDoesNotClaimConnectionAndFailedExchangePersistsUntilRetry() {
        val (store, _) = populated()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        app.getSharedPreferences("sync", 0).edit().putString("pair", "synthetic-pair").commit()
        fun root() = controller.get().window.decorView
        menu(root(), 1)
        button(root(), "Синхронизация с компьютером").performClick(); idle()
        assertTrue(texts(root()).contains("Сопряжение сохранено"))
        assertTrue(texts(root()).contains("Соединение ещё не проверено"))
        val failure = java.net.ConnectException("synthetic-unreachable")
        store.job({ throw failure }, syncOperation = true)
        assertTrue(texts(root()).contains("Проверка соединения…"))
        val end = System.currentTimeMillis() + 5000
        while (store.busy && System.currentTimeMillis() < end) {
            Thread.sleep(20); idle()
        }
        assertFalse(store.busy)
        val error = OperationErrors.message(failure)
        assertEquals("Нет соединения с компьютером.", error)
        assertEquals(error, store.syncSummary)
        val visibleErrors = texts(root()).count { it == error }
        assertEquals(1, visibleErrors)
        assertFalse(texts(root()).contains("Компьютер подключён"))
        assertFalse(texts(root()).contains("Готово к обмену"))
        controller.get().onBackPressed(); idle()
        assertEquals(1, texts(root()).count { it == error })
        val settingsFeedback = find(root()) { it is TextView && it.text.toString() == error }!!
        assertEquals("setting-subtitle", settingsFeedback.tag)
        // A fresh sync notification must not recreate the duplicate above settings.
        store.job({ throw failure }, syncOperation = true)
        val retryEnd = System.currentTimeMillis() + 5000
        while (store.busy && System.currentTimeMillis() < retryEnd) { Thread.sleep(20); idle() }
        assertFalse(store.busy)
        assertSame(settingsFeedback, find(root()) { it is TextView && it.text.toString() == error })
        assertEquals(1, texts(root()).count { it == error })
        controller.get().onBackPressed(); idle()
        assertEquals(1, texts(root()).count { it == error })
        val homeFeedback = find(root()) { it is TextView && it.text.toString() == error } as TextView
        assertEquals(android.view.Gravity.CENTER, homeFeedback.gravity)
        assertEquals(Ui.favorite, homeFeedback.currentTextColor)
        assertTrue(homeFeedback.textSize > 13f * homeFeedback.resources.displayMetrics.scaledDensity)
        menu(root(), 1)
        button(root(), "Синхронизация с компьютером").performClick(); idle()
        store.job({ Unit }); wait(store) // An unrelated operation cannot erase the exchange failure.
        assertEquals(error, store.syncSummary)
        store.job({ Unit }, syncOperation = true) {
            store.javaClass.getDeclaredField("syncStatus").apply { isAccessible = true }.set(store, "Синхронизировано")
        }
        wait(store)
        assertEquals("Синхронизировано", store.syncSummary)
        assertEquals("", store.syncError)
        assertTrue(texts(root()).contains("Синхронизировано"))
        store.clearPairing(); idle()
        assertFalse(texts(root()).contains("Сопряжение сохранено"))
        assertEquals("Сканируйте QR на компьютере", store.syncSummary)
        controller.pause().stop().destroy()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun wait(store: VaultStore) {
        val end = System.currentTimeMillis() + 20000
        while (store.busy && System.currentTimeMillis() < end) {
            Thread.sleep(30); shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
        }
        assertFalse(store.busy); assertEquals("", store.error); idle()
    }
    private fun find(view: View, match: (View) -> Boolean): View? {
        if (match(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), match)?.let { return it }
        return null
    }
    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    private fun control(root: View, description: String) = find(root) { it.contentDescription?.toString() == description }!!
    private fun button(root: View, title: String) = find(root) { it is Button && it.text.toString() == title }
        ?: control(root, title)
    private fun menu(root: View, item: Int) {
        control(root, "Меню").performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        assertTrue(shadowOf(popup).isShowing)
        assertTrue(shadowOf(popup).onMenuItemClickListener.onMenuItemClick(popup.menu.findItem(item)))
        popup.dismiss(); idle()
    }
    private fun populated(): Pair<VaultStore, UUID> {
        val store = (RuntimeEnvironment.getApplication() as KeyShelfApp).store
        store.create("synthetic-master-123"); wait(store)
        val a = Group(UUID.randomUUID(), "Почта")
        val b = Group(UUID.randomUUID(), "Почта")
        val empty = Group(UUID.randomUUID(), "Пустой сервис")
        store.mutate { db ->
            var updated = db.modifyGroup(db.content.group.uuid) { copy(groups = groups + listOf(a, b, empty)) }
            for ((group, title) in listOf(a to "Первая запись", b to "Вторая запись")) {
                updated = VaultCodec.saveRow(updated, null, group.uuid, title, "synthetic-login", "", "account", true,
                    listOf(VaultField("", "Пароль", "synthetic-password", true)))
            }
            updated
        }; wait(store)
        return store to b.uuid
    }
    @Test fun syncNotificationsKeepSearchAndListUntilDatabaseChanges() {
        val (store, _) = populated()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        fun root() = controller.get().window.decorView
        val search = find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText
        search.setText("запись"); search.requestFocus(); search.setSelection(2)
        val list = control(root(), "Список записей") as ScrollView
        val card = control(root(), "Открыть запись Первая запись")
        val fileHash = VaultCodec.hash(store.file.readBytes())
        store.job({ Thread.sleep(60) }, syncOperation = true)
        assertSame(search, find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") })
        assertSame(card, control(root(), "Открыть запись Первая запись"))
        wait(store)
        assertSame(list, control(root(), "Список записей"))
        assertSame(card, control(root(), "Открыть запись Первая запись"))
        assertTrue(search.isFocused); assertEquals(2, search.selectionStart)
        assertEquals(fileHash, VaultCodec.hash(store.file.readBytes()))
        val updated = store.database!!.modifyGroup(store.database!!.content.group.uuid) { copy(name = "Changed synthetic root") }
        // Stand in for a verified remote pull without contacting a real computer.
        store.job({ updated }, syncOperation = true) { db ->
            store.javaClass.getDeclaredField("database").apply { isAccessible = true }.set(store, db)
        }
        wait(store); idle()
        val next = find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText
        assertNotSame(search, next); assertEquals("запись", next.text.toString())
        assertTrue(next.isFocused); assertEquals(2, next.selectionStart)
        store.lock(); idle()
        assertNull(find(root()) { it.contentDescription?.toString() == "Список записей" })
        controller.pause().stop().destroy()
    }
    @Test fun otherEditorHidesApiPriceAndRootIsDisplayOnly() {
        val store = (RuntimeEnvironment.getApplication() as KeyShelfApp).store
        store.create("synthetic-master-123"); wait(store)
        store.mutate { db ->
            val rooted = db.modifyGroup(db.content.group.uuid) { copy(name = "Root") }
            VaultCodec.saveRow(rooted, null, rooted.content.group.uuid, "Synthetic other", "synthetic-orphan-login", "", "other", false,
                listOf(VaultField("", "Поле 1", "", true), VaultField("", "Поле 2", "", true),
                    VaultField("", "Поле 3", "", true), VaultField("", "Дополнительное поле 1", "synthetic-value", true)))
        }; wait(store)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val root = controller.get().window.decorView
        assertEquals("Без сервиса", (control(root, "Выбрать сервис") as Spinner).getItemAtPosition(1))
        control(root, "Открыть запись Synthetic other").performClick(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val paid = find(dialog.window!!.decorView) { it is CheckBox && it.text.toString() == "Платный API" }!!
        val type = control(dialog.window!!.decorView, "Тип записи") as Spinner
        val group = control(dialog.window!!.decorView, "Сервис записи") as Spinner
        assertEquals(View.GONE, find(dialog.window!!.decorView) { it is EditText && it.hint.toString() == "Логин" }!!.visibility)
        assertNotNull(find(dialog.window!!.decorView) { it is EditText && it.text.toString() == "synthetic-orphan-login" })
        assertEquals("Без сервиса", group.selectedItem)
        assertEquals(View.GONE, paid.visibility)
        type.setSelection(1); idle(); assertEquals(View.VISIBLE, paid.visibility)
        type.setSelection(0); idle(); assertEquals(View.GONE, paid.visibility)
        type.setSelection(2); idle(); assertEquals(View.GONE, paid.visibility)
        repeat(3) {
            find(dialog.window!!.decorView) { it is Button && it.text.toString() == "−" }!!.performClick()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
        assertEquals("Root", store.database!!.content.group.name)
        assertEquals("Без сервиса", VaultCodec.rows(store.database!!).single().service)
        assertEquals("", VaultCodec.rows(store.database!!).single().login)
        assertTrue(VaultCodec.rows(store.database!!).single().fields.any { it.value == "synthetic-orphan-login" })
        control(root, "Открыть запись Synthetic other").performClick(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val reopened = ShadowAlertDialog.getLatestAlertDialog()
        for (label in listOf("Поле 1", "Поле 2", "Поле 3"))
            assertNull(find(reopened.window!!.decorView) { it is EditText && it.hint.toString() == label })
        assertNotNull(find(reopened.window!!.decorView) { it is EditText && it.text.toString() == "synthetic-value" })
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        controller.pause().stop().destroy()
    }
    @Test fun typeChangeTransfersValuesInVisibleOrderIncludingLogin() {
        val (store, _) = populated()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        control(controller.get().window.decorView, "Добавить запись").performClick(); idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val root = dialog.window!!.decorView
        fun edit(label: String) = find(root) { it is EditText && it.hint.toString() == label } as EditText
        val type = control(root, "Тип записи") as Spinner
        edit("Название").setText("Transfer")
        edit("Метка").setText(".transfer")
        edit("Логин").setText("first-login")
        edit("Пароль").setText("second-password")
        edit("Секрет 2FA").setText("JBSWY3DPEHPK3PXP")
        type.setSelection(2); idle()
        assertEquals(View.GONE, edit("Логин").visibility)
        assertFalse(edit("Поле 1").transformationMethod is android.text.method.PasswordTransformationMethod)
        assertFalse(edit("Поле 2").transformationMethod is android.text.method.PasswordTransformationMethod)
        assertEquals("first-login", edit("Поле 1").text.toString())
        assertEquals("second-password", edit("Поле 2").text.toString())
        assertEquals("JBSWY3DPEHPK3PXP", edit("Поле 3").text.toString())
        type.setSelection(1); idle()
        assertTrue(edit("API-ключ").transformationMethod is android.text.method.PasswordTransformationMethod)
        assertEquals("first-login", edit("API-ключ").text.toString())
        assertEquals("second-password", edit("Дополнительное поле 1").text.toString())
        assertEquals("JBSWY3DPEHPK3PXP", edit("Дополнительное поле 2").text.toString())
        type.setSelection(0); idle()
        assertEquals(View.VISIBLE, edit("Логин").visibility)
        assertEquals("first-login", edit("Логин").text.toString())
        assertEquals("second-password", edit("Пароль").text.toString())
        assertEquals("JBSWY3DPEHPK3PXP", edit("Секрет 2FA").text.toString())
        type.setSelection(2); idle()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
        val row = VaultCodec.rows(VaultCodec.decode(store.file.readBytes(), "synthetic-master-123")).single { it.title == "Transfer" }
        assertEquals("", row.login)
        assertEquals(".transfer", row.alias)
        assertEquals(listOf("first-login", "second-password", "JBSWY3DPEHPK3PXP"), row.fields.map { it.value })
        controller.pause().stop().destroy()
    }
    @Test fun serviceSelectionFiltersByUuidAndNewRecordDefaultsToSelectedService() {
        val (store, selectedId) = populated()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        fun root() = controller.get().window.decorView
        val services = control(root(), "Выбрать сервис") as Spinner
        assertEquals("Все сервисы", services.selectedItem)
        assertEquals("Все типы", (control(root(), "Тип записей") as Spinner).selectedItem)
        assertTrue(texts(root()).contains("Записей: 2"))
        fun inScroll(view: View): Boolean {
            var parent = view.parent
            while (parent != null) {
                if (parent is android.widget.ScrollView) return true
                parent = parent.parent
            }
            return false
        }
        assertFalse(inScroll(services))
        assertFalse(inScroll(control(root(), "Тип записей")))
        assertFalse(inScroll(control(root(), "Добавить запись")))
        val addRecord = control(root(), "Добавить запись")
        assertTrue(addRecord is ImageButton)
        assertEquals(Ui.dp(controller.get(), 48), addRecord.layoutParams.width)
        assertEquals(addRecord.layoutParams.width, addRecord.layoutParams.height)
        val favorite = control(root(), "Избранное")
        assertTrue(favorite is ImageButton)
        assertEquals(Ui.dp(controller.get(), 48), favorite.layoutParams.width)
        assertEquals(favorite.layoutParams.width, favorite.layoutParams.height)
        val filters = favorite.parent as LinearLayout
        assertSame(filters, control(root(), "Тип записей").parent)
        assertSame(filters, control(root(), "Сбросить фильтры").parent)
        val reset = control(root(), "Сбросить фильтры")
        assertEquals(favorite.layoutParams.width, reset.layoutParams.width)
        assertEquals(reset.layoutParams.width, reset.layoutParams.height)
        val searchRow = (find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") }!!.parent) as LinearLayout
        val panel = filters.parent as LinearLayout
        assertTrue(panel.indexOfChild(services.parent as View) < panel.indexOfChild(filters))
        assertEquals(panel.indexOfChild(filters) + 1, panel.indexOfChild(searchRow))
        assertFalse(inScroll(find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") }!!))
        assertFalse(inScroll(find(root()) { it is TextView && it.text.toString() == "Записей: 2" }!!))
        assertTrue(inScroll(control(root(), "Открыть запись Первая запись")))
        assertTrue(control(root(), "Список записей") is android.widget.ScrollView)
        services.setSelection(2); idle()
        assertTrue(texts(root()).any { it == "Первая запись" })
        assertFalse(texts(root()).any { it == "Вторая запись" })
        services.setSelection(3); idle()
        assertTrue(texts(root()).any { it == "Вторая запись" })
        assertFalse(texts(root()).any { it == "Первая запись" })
        val search = find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText
        search.setText("Первая запись"); assertTrue(texts(root()).contains("Записей: 1"))
        search.setText("")
        services.setSelection(4); idle(); assertTrue(texts(root()).contains("Записей: 0"))
        services.setSelection(3); idle()
        control(root(), "Добавить запись").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val editorGroup = control(dialog.window!!.decorView, "Сервис записи") as Spinner
        assertEquals(2, editorGroup.selectedItemPosition)
        (find(dialog.window!!.decorView) { it is EditText && it.hint.toString() == "Название" } as EditText).setText("Новая в B")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); wait(store)
        assertEquals(selectedId, VaultCodec.rows(store.database!!).single { it.title == "Новая в B" }.group.uuid)
        assertEquals(3, (control(root(), "Выбрать сервис") as Spinner).selectedItemPosition)
        assertTrue(texts(root()).contains("Записей: 2"))
        assertFalse(texts(root()).any { it.contains("synthetic-login") || it.contains("synthetic-password") })
        (find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText).setText("missing")
        (control(root(), "Тип записей") as Spinner).setSelection(3); idle()
        control(root(), "Избранное").performClick()
        control(root(), "Сбросить фильтры").performClick(); idle()
        assertEquals("", (find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText).text.toString())
        assertEquals(0, (control(root(), "Выбрать сервис") as Spinner).selectedItemPosition)
        assertEquals(0, (control(root(), "Тип записей") as Spinner).selectedItemPosition)
        assertFalse(control(root(), "Избранное").isSelected)
        assertTrue(texts(root()).contains("Записей: 3"))
        controller.pause().stop().destroy()
    }
    @Test fun settingsAndSyncBackKeepServiceQueryAndLockClosesVault() {
        populated()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val activity = controller.get()
        fun root() = activity.window.decorView
        (control(root(), "Выбрать сервис") as Spinner).setSelection(3); idle()
        (find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText).setText("Вторая запись")
        assertNull(find(root()) { it is Button && it.text.toString() in listOf("Сервис", "Замок", "Включить плавающую кнопку") })
        menu(root(), 1)
        assertTrue(texts(root()).containsAll(listOf("Настройки", "Резервные копии", "Быстрый доступ")))
        button(root(), "Синхронизация с компьютером").performClick()
        assertTrue(texts(root()).contains("Сканировать QR"))
        button(root(), "Как подключить компьютер").performClick(); idle()
        val help = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(help.window!!.decorView).any { it.contains("одной Wi-Fi сети") })
        help.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        activity.onBackPressed(); idle()
        assertTrue(texts(root()).contains("Резервные копии"))
        control(root(), "Назад").performClick(); idle()
        assertEquals(3, (control(root(), "Выбрать сервис") as Spinner).selectedItemPosition)
        assertTrue(texts(root()).contains("Вторая запись"))
        assertTrue(texts(root()).contains("Записей: 1"))
        control(root(), "Заблокировать хранилище").performClick(); idle()
        assertTrue(texts(root()).contains("Открыть хранилище"))
        assertFalse(control(root(), "Заблокировать хранилище").isEnabled)
        assertNull(find(root()) { it.contentDescription?.toString() == "Выбрать сервис" })
        controller.pause().stop().destroy()
    }
    @Test fun serviceSearchSelectsEmptyServiceWithoutChangingRecordsAndClosesOnLock() {
        val (store, _) = populated()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        fun root() = controller.get().window.decorView
        val hash = VaultCodec.hash(store.file.readBytes())
        val global = find(root()) { it is EditText && it.hint.toString().startsWith("Поиск по") } as EditText
        global.setText("Вторая запись")
        control(root(), "Поиск по сервисам").performClick(); idle()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        val search = control(dialog.window!!.decorView, "Поиск названия сервиса") as EditText
        assertTrue(search.isFocused)
        search.setText("несуществующий")
        assertTrue(texts(dialog.window!!.decorView).contains("Сервисы не найдены"))
        assertEquals("Вторая запись", global.text.toString())
        search.setText("СЕРВИС пустой")
        control(dialog.window!!.decorView, "Выбрать сервис Пустой сервис").performClick(); idle()
        assertEquals("Пустой сервис", (control(root(), "Выбрать сервис") as Spinner).selectedItem)
        assertEquals("", global.text.toString())
        assertTrue(texts(root()).contains("Записей: 0"))
        global.setText("Первая запись")
        assertTrue(texts(root()).contains("Записей: 1")) // Global query searches every service.
        assertEquals(hash, VaultCodec.hash(store.file.readBytes()))
        control(root(), "Поиск по сервисам").performClick(); idle()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        store.lock(); idle()
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }
    @Test fun apiPriceBadgesSurviveKdbxAndAppearInCardsAndDetailsOnlyForApi() {
        val (store, _) = populated()
        store.mutate { db ->
            var updated = db
            for ((title, paid) in listOf("Платный ключ" to true, "Бесплатный ключ" to false)) {
                updated = VaultCodec.saveRow(updated, null, db.content.group.uuid, title, "", "", "api", false,
                    listOf(VaultField("", "API-ключ", "synthetic-api", true)), paid)
            }
            updated
        }; wait(store)
        val restored = VaultCodec.decode(store.file.readBytes(), "synthetic-master-123")
        assertTrue(VaultCodec.rows(restored).single { it.title == "Платный ключ" }.meta.optBoolean("api_paid"))
        assertFalse(VaultCodec.rows(restored).single { it.title == "Бесплатный ключ" }.meta.optBoolean("api_paid"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val root = controller.get().window.decorView
        val paidCard = control(root, "Открыть запись Платный ключ")
        val freeCard = control(root, "Открыть запись Бесплатный ключ")
        assertEquals("$", (control(paidCard, "Платный API") as TextView).text.toString())
        assertEquals("⓪", (control(freeCard, "Бесплатный API") as TextView).text.toString())
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val mini = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {})
        mini.search.setText("ключ")
        assertNotNull(find(mini) { it.contentDescription?.toString() == "Платный API" })
        assertNotNull(find(mini) { it.contentDescription?.toString() == "Бесплатный API" })
        mini.dispose()
        assertNull(find(control(root, "Открыть запись Первая запись")) { it.contentDescription?.toString() == "Бесплатный API" })
        paidCard.performClick(); idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(dialog.window!!.decorView).contains("Платный API"))
        store.lock(); idle(); assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }
    @Test fun overflowAddsServiceAndSettingsStayAvailableWhenLocked() {
        val (store, _) = populated()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        fun root() = controller.get().window.decorView
        menu(root(), 2)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        (find(dialog.window!!.decorView) { it is EditText && it.hint.toString() == "Название сервиса" } as EditText).setText("Новый сервис")
        assertTrue((find(dialog.window!!.decorView) { it is EditText && it.hint.toString() == "Название сервиса" } as EditText).isFocused)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); wait(store)
        val services = control(root(), "Выбрать сервис") as Spinner
        val names = (1 until services.count).map { services.adapter.getItem(it).toString() }
        assertTrue(names.contains("Новый сервис"))
        assertEquals(names.sortedBy { it.lowercase(java.util.Locale.ROOT).replace("ё", "е") }, names)
        control(root(), "Заблокировать хранилище").performClick(); idle()
        control(root(), "Меню").performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        assertEquals(1, popup.menu.size()); popup.dismiss()
        menu(root(), 1)
        assertFalse(texts(root()).contains("Автозаполнение"))
        assertNull(find(root()) { it.contentDescription?.toString() == "Включить автозаполнение" })
        assertNotNull(find(root()) { it.contentDescription?.toString() == "Включить плавающую кнопку" })
        val hideSecrets = control(root(), "Скрывать пароли и ключи") as Switch
        assertTrue(hideSecrets.isChecked)
        hideSecrets.isChecked = false; idle()
        assertFalse(store.hideRecordSecrets)
        button(root(), "Синхронизация с компьютером").performClick()
        assertTrue(texts(root()).contains("Сканировать QR"))
        controller.pause().stop().destroy()
    }
}
