package app.keyshelf

import android.content.Context
import android.text.method.PasswordTransformationMethod
import android.widget.*

/** A small editor for exactly one value in the expanded record. */
class MiniFieldForm(
    context: Context,
    private val store: VaultStore,
    private val row: VaultRow,
    fieldName: String,
    private val finished: () -> Unit
) : LinearLayout(context) {
    private val label = if (fieldName == "Код 2FA") "Секрет 2FA" else fieldName
    private val field = row.displayFields.firstOrNull { it.label == label }
    private val userName = field?.key == "UserName" || label == "Логин"
    private val secret = store.hideRecordSecrets && row.kind != "other" && !userName && field?.secret == true
    val initialFocus = Ui.input(context, label, if (userName) row.login else field?.value.orEmpty(), secret)
    private val feedback = Ui.label(context, "", muted = true)
    private val saveButton = Ui.button(context, "Сохранить") { save() }
    private val cancelButton = Ui.button(context, "Отмена") { cancel() }
    private val showButton = Ui.button(context, "Показать") {
        val visible = initialFocus.transformationMethod != null
        initialFocus.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
        initialFocus.setSelection(initialFocus.text.length)
        showButtonText(visible)
    }
    var isSaving = false; private set
    private var disposed = false
    private val updates: () -> Unit = { refresh() }
    init {
        Ui.dialogAction(saveButton, primary = true)
        Ui.dialogAction(cancelButton)
        Ui.dialogAction(showButton)
        orientation = VERTICAL
        setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        setBackgroundColor(Ui.background)
        addView(Ui.label(context, "Редактировать: $label", 20f))
        addView(initialFocus)
        if (secret) addView(showButton)
        addView(feedback)
        val actions = LinearLayout(context)
        actions.addView(cancelButton, LayoutParams(0, Ui.dp(context, 48), 1f))
        actions.addView(saveButton, LayoutParams(0, Ui.dp(context, 48), 1f).apply { marginStart = Ui.dp(context, 8) })
        addView(actions, LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 12) })
        store.listen(updates); refresh()
    }
    private fun showButtonText(visible: Boolean) { showButton.text = if (visible) "Скрыть" else "Показать" }
    private fun save() {
        if (store.busy || store.database == null || label != "Логин" && field == null) return
        val value = initialFocus.text.toString()
        if (label == "Секрет 2FA" && value.isNotEmpty() && runCatching { Totp.code(value) }.isFailure) {
            feedback.text = "Неверный секрет 2FA"; feedback.visibility = VISIBLE; return
        }
        val user = if (userName) value else row.login
        val values = row.fields.map { if (!userName && it.key == field?.key) it.copy(value = value) else it }
        isSaving = true
        store.mutate { VaultCodec.saveRow(it, row, row.group.uuid, row.title, user,
            row.entry.fields.notes?.content.orEmpty(), row.kind, row.favorite, values,
            row.meta.optBoolean("api_paid"), preserveLayout = true) }
    }
    private fun refresh() {
        if (disposed) return
        if (store.database == null) { dispose(); finished(); return }
        if (isSaving && !store.busy) {
            isSaving = false
            if (store.error.isEmpty()) { dispose(); finished(); return }
        }
        saveButton.isEnabled = !store.busy
        cancelButton.isEnabled = !isSaving; initialFocus.isEnabled = !isSaving; showButton.isEnabled = !isSaving
        feedback.text = if (isSaving) "Сохранение…" else store.error
        feedback.visibility = if (feedback.text.isEmpty()) GONE else VISIBLE
    }
    fun cancel() { if (!isSaving) { dispose(); finished() } }
    fun dispose() {
        if (disposed) return
        disposed = true; store.unlisten(updates); initialFocus.cancelKeyboard(); initialFocus.text.clear()
    }
}
