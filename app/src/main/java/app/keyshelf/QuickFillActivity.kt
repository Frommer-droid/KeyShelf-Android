package app.keyshelf

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

/** Autofill always opens mini search; the launcher is the only full-mode entry. */
class QuickFillActivity : Activity() {
    private lateinit var panel: QuickSearchPanel
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        FloatingService.instance?.hideForApplicationUi()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val store = (application as KeyShelfApp).store
        panel = QuickSearchPanel(this, store, ::returnFill,
            { row, field -> startActivity(MiniEditActivity.intent(this, row, field)) }, ::finish) {
            window.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        setContentView(panel)
        window.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        panel.focusSearch()
    }
    private fun returnFill(row: VaultRow, selected: String) {
        val ids = intent.getParcelableArrayListExtra<AutofillId>("fill_ids") ?: return
        val roles = intent.getStringArrayListExtra("fill_roles") ?: return
        if (ids.isEmpty() || ids.size != roles.size) return
        if ((application as KeyShelfApp).store.database == null) return
        val password = row.fields.firstOrNull { it.label == "Пароль" }?.value ?: selected
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, row.title) }
        val dataset = Dataset.Builder(presentation)
        ids.forEachIndexed { i, id -> dataset.setValue(id, AutofillValue.forText(when (roles[i]) {
            "password" -> password; "login" -> row.login; else -> selected })) }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, FillResponse.Builder().addDataset(dataset.build()).build()))
        finish()
    }
    override fun onDestroy() { if (::panel.isInitialized) panel.dispose(); super.onDestroy() }
}
