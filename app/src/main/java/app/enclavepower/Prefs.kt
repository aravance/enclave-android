package app.enclavepower

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var address: String
        get() = prefs.getString(KEY_ADDRESS, DEFAULT_ADDRESS)!!
        set(value) = prefs.edit().putString(KEY_ADDRESS, value).apply()

    var onWake: Boolean
        get() = prefs.getBoolean(KEY_ON_WAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_ON_WAKE, value).apply()

    var triggerPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, DEFAULT_PACKAGES)!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_PACKAGES, value).apply()

    var lastResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "Never triggered")!!
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_ON_WAKE = "on_wake"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_LAST_RESULT = "last_result"

        const val DEFAULT_ADDRESS = "00:07:80:C4:46:09"
        val DEFAULT_PACKAGES = setOf("org.jellyfin.androidtv", "com.plexapp.android")
    }
}
