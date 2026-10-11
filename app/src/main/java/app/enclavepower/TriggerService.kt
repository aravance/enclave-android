package app.enclavepower

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent

/**
 * An accessibility service is used because the system keeps it bound from boot,
 * and it is told about foreground app changes without polling.
 */
class TriggerService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var a2dpGuard: A2dpGuard
    private var foregroundPackage: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && prefs.onWake) {
                PowerOn.request(context, "wake")
            }
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        a2dpGuard = A2dpGuard(this, prefs).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Overlays such as the volume bar should not count as leaving the app.
        if (pkg == "com.android.systemui" || pkg == foregroundPackage) return
        foregroundPackage = pkg
        if (pkg in prefs.triggerPackages) {
            PowerOn.request(this, pkg)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterReceiver(screenReceiver)
        a2dpGuard.stop()
        return super.onUnbind(intent)
    }
}
