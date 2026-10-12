package app.keyshelf

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.*
import android.view.autofill.AutofillId
import android.widget.RemoteViews

class FillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        // One entry point per field: the accessibility icon takes priority.
        // Its connected service shares this application process with Autofill.
        if (!shouldOfferAutofill()) { callback.onSuccess(null); return }
        val structure = request.fillContexts.lastOrNull()?.structure ?: run { callback.onSuccess(null); return }
        if (structure.activityComponent.packageName == packageName) { callback.onSuccess(null); return }
        val nodes = mutableListOf<AssistStructure.ViewNode>()
        fun visit(node: AssistStructure.ViewNode) {
            if (node.autofillId != null && node.autofillType == android.view.View.AUTOFILL_TYPE_TEXT && node.isEnabled) nodes.add(node)
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
        if (nodes.isEmpty() || cancellationSignal.isCanceled) { callback.onSuccess(null); return }
        val ids = ArrayList<AutofillId>()
        val roles = ArrayList<String>()
        nodes.forEach { node ->
            val hints = (node.autofillHints?.joinToString(" ").orEmpty() + " " + node.idEntry.orEmpty() + " " + node.hint.orEmpty()).lowercase()
            val variation = node.inputType and android.text.InputType.TYPE_MASK_VARIATION
            val inputClass = node.inputType and android.text.InputType.TYPE_MASK_CLASS
            val passwordInput = inputClass == android.text.InputType.TYPE_CLASS_TEXT && variation in listOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
                inputClass == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            val role = when {
                passwordInput || "password" in hints || "парол" in hints -> "password"
                "username" in hints || "email" in hints || "login" in hints || "логин" in hints -> "login"
                else -> "selected"
            }
            // Avoid filling unrelated fields in a multi-field form.
            if (node.isFocused || role != "selected") { ids.add(node.autofillId!!); roles.add(role) }
        }
        if (ids.isEmpty()) { callback.onSuccess(null); return }
        val intent = authenticationIntent(this).apply {
            putParcelableArrayListExtra("fill_ids", ids); putStringArrayListExtra("fill_roles", roles)
            putExtra("destination", structure.activityComponent.packageName)
        }
        val pending = PendingIntent.getActivity(this, nextRequest++, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, "KeyShelf · найти запись") }
        callback.onSuccess(FillResponse.Builder().setAuthentication(ids.toTypedArray(), pending.intentSender, presentation).build())
    }
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) { callback.onSuccess() }
    companion object {
        private var nextRequest = 100
        internal fun shouldOfferAutofill() = FloatingService.instance == null
        internal fun authenticationIntent(context: android.content.Context) = Intent(context, QuickFillActivity::class.java)
    }
}
