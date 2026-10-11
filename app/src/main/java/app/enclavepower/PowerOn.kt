package app.enclavepower

import android.content.Context
import android.util.Log
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs power-on requests one at a time and records the outcome. */
object PowerOn {
    private const val TAG = "EnclavePower"
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    fun request(context: Context, reason: String, onDone: ((String) -> Unit)? = null) {
        // Triggers arrive in bursts (wake, then the app coming forward); one attempt covers them.
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                val prefs = Prefs(appContext)
                val summary = when (val result = EnclaveClient.powerOn(appContext, prefs.address)) {
                    is EnclaveClient.Result.On -> "On (firmware ${result.firmware})"
                    is EnclaveClient.Result.Unconfirmed -> "Sent, not confirmed (firmware ${result.firmware})"
                    is EnclaveClient.Result.Failed -> "Failed: ${result.reason}"
                }
                Log.i(TAG, "$reason -> $summary")
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date())
                val line = "$time, $reason: $summary"
                prefs.lastResult = line
                onDone?.invoke(line)
            } finally {
                running.set(false)
            }
        }
    }
}
