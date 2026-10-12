package app.keyshelf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

class MiniEditActivity : Activity() {
    private var form: MiniFieldForm? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val store = (application as KeyShelfApp).store
        val db = store.database ?: run { finish(); return }
        val row = VaultCodec.rows(db).firstOrNull { it.id == intent.getStringExtra("record_id") }
            ?: run { finish(); return }
        val content = MiniFieldForm(this, store, row, intent.getStringExtra("field_name").orEmpty(), ::finish)
        form = content; setContentView(content)
        window.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        content.initialFocus.setSelection(content.initialFocus.text.length)
        content.initialFocus.focusKeyboard { !isFinishing && store.database != null }
    }
    override fun onUserInteraction() { super.onUserInteraction(); (application as KeyShelfApp).store.touch() }
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations && form?.isSaving != true) form?.cancel()
    }
    @Deprecated("Legacy Back callback")
    override fun onBackPressed() { form?.cancel() ?: finish() }
    override fun onDestroy() {
        form?.dispose(); form = null
        if (isFinishing && intent.getBooleanExtra("from_overlay", false)) FloatingService.instance?.finishMiniEdit()
        super.onDestroy()
    }
    companion object {
        fun intent(context: Context, row: VaultRow, field: String) = Intent(context, MiniEditActivity::class.java)
            .putExtra("record_id", row.id).putExtra("field_name", field)
    }
}
