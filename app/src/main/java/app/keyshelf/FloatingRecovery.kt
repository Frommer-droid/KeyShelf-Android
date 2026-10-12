package app.keyshelf

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/** Personal ADB builds may repair MIUI's enabled-but-disconnected service. */
object FloatingRecovery {
    private var checking = false
    private val handler = Handler(Looper.getMainLooper())

    fun check(context: Context) {
        if (checking) return
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        checking = true
        handler.postDelayed({
            try { recover(app) } catch (error: Exception) {
                Log.w("KeyShelfService", "Service recovery failed", error)
            } finally { checking = false }
        }, 1000)
    }

    private fun recover(context: Context) {
        val resolver = context.contentResolver
        val component = ComponentName(context, FloatingService::class.java)
        val prefs = context.getSharedPreferences("floating_recovery", Context.MODE_PRIVATE)
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        fun selected() = Settings.Secure.getString(resolver, key).orEmpty().split(':').filter { it.isNotBlank() }
        fun own(value: String) = ComponentName.unflattenFromString(value) == component
        fun restore() {
            val latest = selected()
            if (latest.none(::own)) {
                check(Settings.Secure.putString(resolver, key, (latest + component.flattenToString()).joinToString(":")))
            }
            check(prefs.edit().remove("pending").commit())
        }
        // A process death in the short rebind interval must not lose the user's selection.
        if (prefs.getBoolean("pending", false)) { restore(); return }
        if (FloatingService.instance != null ||
            Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return
        val current = selected()
        if (current.none(::own)) return
        check(prefs.edit().putBoolean("pending", true).commit())
        if (!Settings.Secure.putString(resolver, key, current.filterNot(::own).joinToString(":"))) {
            prefs.edit().remove("pending").commit()
            return
        }
        // Give AccessibilityManager time to observe the removal before restoring it.
        handler.postDelayed({
            try { restore() } catch (error: Exception) {
                Log.w("KeyShelfService", "Service selection restore failed", error)
            }
        }, 600)
    }
}
