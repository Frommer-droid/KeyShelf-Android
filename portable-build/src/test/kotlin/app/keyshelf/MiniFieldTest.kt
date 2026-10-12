package app.keyshelf

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.os.Looper
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = KeyShelfApp::class, shadows = [WindowsAtomicFileShadow::class])
class MiniFieldTest {
    private fun wait(store: VaultStore) {
        val end = System.currentTimeMillis() + 20000
        while (store.busy && System.currentTimeMillis() < end) {
            Thread.sleep(30); shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
        }
        assertFalse(store.busy); assertEquals("", store.error)
    }
    private fun record(kind: String): Triple<KeyShelfApp, VaultStore, VaultRow> {
        val app = RuntimeEnvironment.getApplication() as KeyShelfApp
        val store = app.store
        store.create("synthetic-master-123"); wait(store)
        val values = if (kind == "api") listOf(VaultField("", "API-ключ", "synthetic-api", true))
            else listOf(VaultField("", "Пароль", "synthetic-password", true), VaultField("", "Секрет 2FA", "", true))
        store.mutate { db -> VaultCodec.saveRow(db, null, db.content.group.uuid, "Synthetic record", "synthetic-login",
            "synthetic-note", kind, true, values, paid = kind == "api") }
        wait(store)
        return Triple(app, store, VaultCodec.rows(store.database!!).single())
    }
    private fun button(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), text)?.let { return it }
        return null
    }
    private fun inputs(view: View): Int = (if (view is EditText) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { inputs(view.getChildAt(it)) } else 0
    private fun description(view: View, caption: String): View? {
        if (view.contentDescription?.toString() == caption) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) description(view.getChildAt(i), caption)?.let { return it }
        return null
    }
    private fun editOnly(kind: String, label: String) {
        val (app, store, old) = record(kind)
        var closed = false
        val form = MiniFieldForm(app, store, old, label) { closed = true }
        assertEquals(1, inputs(form))
        form.initialFocus.setText("synthetic-edited-value")
        button(form, "Сохранить")!!.performClick(); wait(store)
        assertTrue(closed); assertEquals("", form.initialFocus.text.toString())
        val saved = VaultCodec.rows(VaultCodec.decode(store.file.readBytes(), "synthetic-master-123")).single()
        assertEquals(old.id, saved.id); assertEquals(old.title, saved.title); assertEquals(old.group.uuid, saved.group.uuid)
        assertEquals(old.kind, saved.kind); assertEquals(old.favorite, saved.favorite)
        assertEquals(old.meta.optBoolean("api_paid"), saved.meta.optBoolean("api_paid"))
        assertEquals(old.meta.optJSONArray("field_layout")?.toString(), saved.meta.optJSONArray("field_layout")?.toString())
        assertEquals(old.entry.fields.notes?.content, saved.entry.fields.notes?.content)
        if (label == "Логин") {
            assertEquals("synthetic-edited-value", saved.login)
            assertEquals(old.fields.map { it.label to it.value }, saved.fields.map { it.label to it.value })
        } else {
            assertEquals(old.login, saved.login)
            assertEquals("synthetic-edited-value", saved.fields.first { it.label == label }.value)
            assertEquals(old.fields.filter { it.label != label }.map { it.label to it.value }, saved.fields.filter { it.label != label }.map { it.label to it.value })
        }
    }
    @Test fun passwordEditChangesOnlyPassword() = editOnly("account", "Пароль")
    @Test fun loginEditChangesOnlyLogin() = editOnly("account", "Логин")
    @Test fun apiEditPreservesPaidFlagAndMetadata() = editOnly("api", "API-ключ")
    @Test fun cancelAndLockScrubDraftWithoutWriting() {
        val (app, store, row) = record("account")
        val hash = VaultCodec.hash(store.file.readBytes())
        var closed = false
        val form = MiniFieldForm(app, store, row, "Пароль") { closed = true }
        form.initialFocus.setText("synthetic-unsaved"); button(form, "Отмена")!!.performClick()
        assertTrue(closed); assertEquals("", form.initialFocus.text.toString())
        assertEquals(hash, VaultCodec.hash(store.file.readBytes()))
        closed = false
        val other = MiniFieldForm(app, store, row, "Пароль") { closed = true }
        other.initialFocus.setText("synthetic-unsaved"); store.lock()
        assertTrue(closed); assertEquals("", other.initialFocus.text.toString())
        assertEquals(hash, VaultCodec.hash(store.file.readBytes()))
    }
    @Test fun floatingControlUsesDrawableAndMiniIntentContainsNoValue() {
        val (app, _, row) = record("account")
        var clicked = false
        // Config.NONE has no app resource table; use a framework fixture drawable.
        val icon = FloatingIcon.create(app, android.R.drawable.ic_lock_lock) { clicked = true }
        assertNotNull(icon.drawable); assertTrue(icon.clipToOutline)
        icon.layout(0, 0, 56, 56)
        val outline = android.graphics.Outline()
        icon.outlineProvider.getOutline(icon, outline)
        val bounds = android.graphics.Rect()
        assertTrue(outline.getRect(bounds))
        assertEquals(android.graphics.Rect(0, 0, 56, 56), bounds)
        assertEquals(28f, outline.radius, 0f)
        assertNull(icon.backgroundTintList); assertNull(icon.imageTintList)
        icon.performClick(); assertTrue(clicked)
        val intent = MiniEditActivity.intent(app, row, "Пароль")
        assertEquals(MiniEditActivity::class.java.name, intent.component!!.className)
        assertEquals(setOf("record_id", "field_name"), intent.extras!!.keySet())
    }
    @Test fun pencilEditReturnsToSameExpandedRecordWithUpdatedValue() {
        val (app, store, _) = record("account")
        lateinit var panel: QuickSearchPanel
        var editor: MiniFieldForm? = null
        panel = QuickSearchPanel(app, store, { _, _ -> }, { row, label ->
            panel.dispose(); editor = MiniFieldForm(app, store, row, label) {}
        }, {}, state = store.quickSearchState)
        panel.search.setText("Synthetic")
        description(panel, "Раскрыть запись KeyShelf · Synthetic record")!!.performClick()
        description(panel, "Редактировать Пароль")!!.performClick()
        assertEquals(1, inputs(editor!!))
        editor!!.initialFocus.setText("synthetic-new-password")
        button(editor!!, "Сохранить")!!.performClick(); wait(store)
        val restored = QuickSearchPanel(app, store, { _, _ -> }, { _, _ -> }, {}, state = store.quickSearchState)
        assertEquals("Synthetic", restored.search.text.toString())
        assertNotNull(description(restored, "Свернуть запись KeyShelf · Synthetic record"))
        assertEquals("••••••••", (description(restored, "Пароль") as android.widget.TextView).text.toString())
        store.setHideRecordSecrets(false)
        assertEquals("synthetic-new-password", (description(restored, "Пароль") as android.widget.TextView).text.toString())
        restored.dispose()
    }
}
