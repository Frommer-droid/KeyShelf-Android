package app.keyshelf

import android.widget.TextView
import android.view.ViewGroup
import android.view.View
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
class AndroidSmokeTest {
    private fun texts(view: View): List<String> {
        val result = mutableListOf<String>()
        if (view is TextView) result.add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) result.addAll(texts(view.getChildAt(i)))
        return result
    }
    @Test fun lockedActivityRendersAndDestroys() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val values = texts(activity.window.decorView)
        assertTrue(values.contains("Создать хранилище"))
        assertFalse(values.contains("Синхронизация с компьютером"))
        assertFalse(values.contains("Включить плавающую кнопку"))
        controller.pause().stop().destroy()
    }
    @Test fun encryptedCreateLockAndReopenOnAndroid13() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val store = app.store
        store.create("synthetic-master-123")
        fun waitForJob() {
            val limit = System.currentTimeMillis() + 20000
            while (store.busy && System.currentTimeMillis() < limit) {
                Thread.sleep(50); shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            }
            assertFalse(store.busy)
            assertEquals("", store.error)
        }
        waitForJob()
        assertNotNull(store.database)
        assertTrue(store.file.exists())
        store.lock(); assertNull(store.database)
        store.unlock("synthetic-master-123"); waitForJob(); assertNotNull(store.database)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(texts(controller.get().window.decorView).contains("Записей: 0"))
        fun find(view: View, match: (View) -> Boolean): View? {
            if (match(view)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), match)?.let { return it }
            return null
        }
        val add = find(controller.get().window.decorView) { it.contentDescription?.toString() == "Добавить запись" }
        assertNotNull(add); add!!.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        val draft = find(dialog.window!!.decorView) { it is android.widget.EditText && it.hint?.toString() == "Пароль" } as android.widget.EditText
        val title = find(dialog.window!!.decorView) { it is android.widget.EditText && it.hint?.toString() == "Название" } as android.widget.EditText
        val alias = find(dialog.window!!.decorView) { it is android.widget.EditText && it.hint?.toString() == "Метка" } as android.widget.EditText
        title.setText("Синтетическая запись")
        alias.setText(" .p ")
        draft.setText("synthetic-saved-secret")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        waitForJob()
        assertEquals("Синтетическая запись", VaultCodec.rows(store.database!!).single().title)
        assertEquals(".p", VaultCodec.rows(store.database!!).single().alias)
        assertEquals("Синтетическая запись", VaultCodec.search(VaultCodec.rows(store.database!!), ".p").single().title)
        assertEquals(".p", VaultCodec.rows(VaultCodec.decode(store.file.readBytes(), "synthetic-master-123")).single().alias)
        assertEquals("synthetic-saved-secret", VaultCodec.rows(store.database!!).single().fields.first { it.label == "Пароль" }.value)
        assertEquals("", draft.text.toString())
        assertEquals("", alias.text.toString())
        find(controller.get().window.decorView) { it.contentDescription?.toString() == "Добавить запись" }!!.performClick()
        val lockedDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val lockedDraft = find(lockedDialog.window!!.decorView) { it is android.widget.EditText && it.hint?.toString() == "Пароль" } as android.widget.EditText
        lockedDraft.setText("synthetic-draft-secret")
        store.lock()
        assertFalse(lockedDialog.isShowing)
        assertEquals("", lockedDraft.text.toString())
        controller.pause().stop().destroy()
    }
    @Test fun externalSyncHostIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SyncClient.validateHost("8.8.8.8") }
        assertThrows(IllegalArgumentException::class.java) { SyncClient.validateHost("example.com") }
        SyncClient.validateHost("192.168.1.10")
        SyncClient.validateHost("10.0.0.10")
    }
    @Test fun fullListHidesValuesUntilExplicitOpenAcrossSearchAndReopen() {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val store = app.store
        fun waitForJob() {
            val limit = System.currentTimeMillis() + 20000
            while (store.busy && System.currentTimeMillis() < limit) {
                Thread.sleep(30); shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
            }
            assertFalse(store.busy); assertEquals("", store.error)
        }
        store.create("synthetic-master-123"); waitForJob()
        store.mutate { db ->
            val first = VaultCodec.saveRow(db, null, db.content.group.uuid, "С логином", "synthetic-private-login", "synthetic-note", "account", true,
                listOf(VaultField("", "Пароль", "synthetic-private-password", true)))
            VaultCodec.saveRow(first, null, first.content.group.uuid, "Без логина", "", "", "api", false,
                listOf(VaultField("", "API-ключ", "synthetic-private-api", true)))
        }; waitForJob()
        fun find(view: View, match: (View) -> Boolean): View? {
            if (match(view)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), match)?.let { return it }
            return null
        }
        fun assertHidden(view: View) {
            assertFalse(texts(view).any { it.contains("synthetic-private") || it.contains("synthetic-note") })
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = controller.get().window.decorView
        assertTrue(texts(root).contains("Записей: 2")); assertHidden(root)
        val header = find(root) { it.contentDescription?.toString() == "Открыть запись С логином" }!!
        header.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val details = dialog.window!!.decorView
        val loginValue = find(details) { it is android.widget.TextView && it.text.toString() == "synthetic-private-login" } as android.widget.TextView
        assertEquals(1, loginValue.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, loginValue.ellipsize)
        assertFalse(texts(details).contains("synthetic-private-password"))
        val reveal = find(details) { it.contentDescription?.toString() == "Показать Пароль" }!!
        reveal.performClick()
        assertTrue(texts(details).contains("synthetic-private-password"))
        reveal.performClick()
        assertFalse(texts(details).contains("synthetic-private-password"))
        find(details) { it.contentDescription?.toString() == "Копировать Логин" }!!.performClick()
        val clipboard = controller.get().getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("synthetic-private-login", clipboard.primaryClip!!.getItemAt(0).text.toString())
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing); assertHidden(root)
        header.performClick()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val editor = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val save = editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        val cancel = editor.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
        val delete = editor.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
        assertSame(save.parent, cancel.parent); assertSame(save.parent, delete.parent)
        val actions = save.parent as android.widget.LinearLayout
        actions.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST))
        actions.layout(0, 0, actions.measuredWidth, actions.measuredHeight)
        assertEquals(android.widget.LinearLayout.HORIZONTAL, actions.orientation)
        assertEquals(save.top, delete.top); assertEquals(save.top, cancel.top)
        assertTrue(delete.left < cancel.left && cancel.left < save.left)
        cancel.performClick(); shadowOf(Looper.getMainLooper()).idle(); assertFalse(editor.isShowing); assertHidden(root)
        val search = find(root) { it is android.widget.EditText && it.hint?.toString()?.startsWith("Поиск по") == true } as android.widget.EditText
        search.setText("С логином")
        assertTrue(texts(root).contains("Записей: 1")); assertHidden(root)
        find(root) { it.contentDescription?.toString() == "Очистить поиск" }!!.performClick()
        assertEquals("", search.text.toString()); assertTrue(texts(root).contains("Записей: 2"))
        val favorites = find(root) { it.contentDescription?.toString() == "Избранное" } as android.widget.ImageButton
        favorites.performClick()
        assertTrue(favorites.isSelected)
        assertTrue(texts(root).contains("Записей: 1")); assertHidden(root)
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(texts(reopened.get().window.decorView).contains("Записей: 2"))
        assertHidden(reopened.get().window.decorView)
        reopened.pause().stop().destroy()
    }
    @Test fun connectionErrorsAreActionableAndLogsContainOnlyTypes() {
        val timeout = java.net.SocketTimeoutException("synthetic-secret-never-log")
        val wrapped = java.io.IOException("synthetic-private-host", timeout)
        assertTrue(OperationErrors.message(wrapped).startsWith("Компьютер не ответил"))
        assertFalse(OperationErrors.types(wrapped).contains("synthetic"))
        assertTrue(OperationErrors.types(wrapped).contains("java.net.SocketTimeoutException"))
        assertTrue(OperationErrors.message(javax.net.ssl.SSLHandshakeException("synthetic-token"))
            .contains("защищённое соединение"))
    }
}
