package app.enclavepower

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var serviceStatus: TextView
    private lateinit var settingsButton: Button
    private lateinit var lastResult: TextView
    private lateinit var pairStatus: TextView

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updatePairStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }

        serviceStatus = TextView(this)
        column.addView(serviceStatus)
        // Android TV only stubs ACTION_ACCESSIBILITY_SETTINGS, so the nearest reachable screen is Settings itself.
        settingsButton = Button(this).apply {
            text = "Open Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        column.addView(settingsButton)

        pairStatus = TextView(this)
        column.addView(pairStatus)
        column.addView(
            Button(this).apply {
                text = "Pair with Enclave"
                setOnClickListener { pair() }
            },
        )

        lastResult = TextView(this)
        column.addView(lastResult)
        column.addView(
            Button(this).apply {
                text = "Turn Enclave on now"
                setOnClickListener {
                    lastResult.text = "Connecting to ${prefs.address}…"
                    PowerOn.request(this@MainActivity, "manual") { line ->
                        runOnUiThread { lastResult.text = "Last: $line" }
                    }
                }
            },
        )

        column.addView(
            Switch(this).apply {
                text = "Turn on when the Shield wakes"
                isChecked = prefs.onWake
                setOnCheckedChangeListener { _, checked -> prefs.onWake = checked }
            },
        )

        column.addView(
            TextView(this).apply {
                text = "Turn on when these apps open:"
                setPadding(0, pad, 0, 0)
            },
        )
        val selected = prefs.triggerPackages.toMutableSet()
        for ((pkg, label) in installedApps(selected)) {
            column.addView(
                CheckBox(this).apply {
                    text = label
                    isChecked = pkg in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(pkg) else selected.remove(pkg)
                        prefs.triggerPackages = selected
                    }
                },
            )
        }

        setContentView(ScrollView(this).apply { addView(column) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        serviceStatus.text =
            if (enabled) {
                "Trigger service: enabled"
            } else {
                "Trigger service: disabled. In Settings, go to Device Preferences > Accessibility and turn on Enclave Power."
            }
        settingsButton.visibility = if (enabled) View.GONE else View.VISIBLE
        lastResult.text = "Last: ${prefs.lastResult}"
        registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        updatePairStatus()
    }

    override fun onPause() {
        unregisterReceiver(bondReceiver)
        super.onPause()
    }

    private fun enclave(): BluetoothDevice? = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(prefs.address)
    } catch (e: IllegalArgumentException) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun updatePairStatus() {
        val state = try {
            enclave()?.bondState
        } catch (e: SecurityException) {
            null
        }
        pairStatus.text = "Enclave ${prefs.address}: " + when (state) {
            BluetoothDevice.BOND_BONDED -> "paired"
            BluetoothDevice.BOND_BONDING -> "pairing… accept the prompt"
            BluetoothDevice.BOND_NONE -> "not paired"
            else -> "Bluetooth unavailable"
        }
    }

    @SuppressLint("MissingPermission")
    private fun pair() {
        try {
            if (enclave()?.createBond() != true) updatePairStatus()
        } catch (e: SecurityException) {
            pairStatus.text = "Bluetooth permission not granted"
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, TriggerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Launchable apps as (package, label), selected ones first; keeps selected apps that are not installed. */
    private fun installedApps(selected: Set<String>): List<Pair<String, String>> {
        val apps = HashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in packageManager.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg != packageName) apps.putIfAbsent(pkg, info.loadLabel(packageManager).toString())
            }
        }
        for (pkg in selected) apps.putIfAbsent(pkg, "$pkg (not installed)")
        return apps.toList().sortedWith(compareBy({ it.first !in selected }, { it.second.lowercase() }))
    }
}
