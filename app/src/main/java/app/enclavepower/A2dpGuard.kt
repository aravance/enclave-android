package app.enclavepower

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * The Enclave is also a Bluetooth speaker, and it has to be bonded for the
 * remote-control channel to work. Once bonded, Android connects to it for audio
 * and routes everything there instead of HDMI. This drops that audio link
 * whenever it comes up.
 *
 * There is no public API for this, so it goes through BluetoothA2dp's hidden
 * methods: setConnectionPolicy (needs a privileged permission, so it normally
 * fails) and then disconnect.
 */
@SuppressLint("MissingPermission")
class A2dpGuard(private val context: Context, private val prefs: Prefs) {
    private var a2dp: BluetoothA2dp? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            a2dp = proxy as BluetoothA2dp
            proxy.connectedDevices.forEach(::dropIfEnclave)
        }

        override fun onServiceDisconnected(profile: Int) {
            a2dp = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
            if (state != BluetoothProfile.STATE_CONNECTED) return
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            dropIfEnclave(device)
        }
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.getProfileProxy(context, listener, BluetoothProfile.A2DP)
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        a2dp?.let {
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?.closeProfileProxy(BluetoothProfile.A2DP, it)
        }
        a2dp = null
    }

    private fun dropIfEnclave(device: BluetoothDevice) {
        val proxy = a2dp ?: return
        if (!device.address.equals(prefs.address, ignoreCase = true)) return
        val forbidden = call(proxy, "setConnectionPolicy", device, CONNECTION_POLICY_FORBIDDEN)
        val disconnected = forbidden || call(proxy, "disconnect", device)
        Log.i(TAG, "Dropped Bluetooth audio link to ${device.address}: forbidden=$forbidden disconnected=$disconnected")
    }

    private fun call(proxy: BluetoothA2dp, name: String, vararg args: Any): Boolean = try {
        val types = args.map { if (it is Int) Int::class.javaPrimitiveType else BluetoothDevice::class.java }
        BluetoothA2dp::class.java.getMethod(name, *types.toTypedArray()).invoke(proxy, *args) == true
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "$name unavailable: ${e.cause ?: e}")
        false
    }

    private companion object {
        const val TAG = "EnclavePower"
        const val CONNECTION_POLICY_FORBIDDEN = 0
    }
}
