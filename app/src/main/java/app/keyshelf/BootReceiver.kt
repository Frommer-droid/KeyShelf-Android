package app.keyshelf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Runs after the first device unlock; never opens an activity. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        try {
            FloatingRecovery.check(context)
            (context.applicationContext as KeyShelfApp).store.autoUnlock()
        } finally {
            // Keep the receiver's process alive through the short recovery interval.
            Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 3000)
        }
    }
}
