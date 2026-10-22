package pl.karoonotify.karoo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class KarooApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KarooSettings.init(this)
    }
}

object KarooSettings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        }
    }

    /** ID telefonu, z którym Karoo jest sparowane (puste = połącz z pierwszym znalezionym). */
    var pairedPhoneId: String
        get() = prefs.getString("paired_id", "") ?: ""
        set(v) = prefs.edit().putString("paired_id", v).apply()

    /** Dodatkowo pokazuj wyskakujący baner w trakcie jazdy. */
    var popupEnabled: Boolean
        get() = prefs.getBoolean("popup", false)
        set(v) = prefs.edit().putBoolean("popup", v).apply()

    /** Usuwaj z Karoo powiadomienia odrzucone na telefonie. */
    var removeDismissed: Boolean
        get() = prefs.getBoolean("remove_dismissed", true)
        set(v) = prefs.edit().putBoolean("remove_dismissed", v).apply()
}
