package pl.karoonotify.phone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }
}

/** Ustawienia aplikacji na telefonie. */
object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains("phone_id")) {
            val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString("phone_id", bytes.joinToString("") { "%02X".format(it) }).apply()
        }
    }

    /** 8 znaków hex, widoczne na telefonie i na Karoo – do sparowania. */
    val phoneId: String get() = prefs.getString("phone_id", "00000000")!!

    val phoneIdBytes: ByteArray
        get() = phoneId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    var bridgeEnabled: Boolean
        get() = prefs.getBoolean("bridge_enabled", true)
        set(v) = prefs.edit().putBoolean("bridge_enabled", v).apply()

    /** Pomijaj powiadomienia stałe (muzyka, nawigacja, nagrywanie Stravy, itp.). */
    var skipOngoing: Boolean
        get() = prefs.getBoolean("skip_ongoing", true)
        set(v) = prefs.edit().putBoolean("skip_ongoing", v).apply()

    /** Pomijaj ciche powiadomienia (niski priorytet). */
    var skipSilent: Boolean
        get() = prefs.getBoolean("skip_silent", false)
        set(v) = prefs.edit().putBoolean("skip_silent", v).apply()

    var excludedPackages: Set<String>
        get() = prefs.getStringSet("excluded", DEFAULT_EXCLUDED) ?: DEFAULT_EXCLUDED
        set(v) = prefs.edit().putStringSet("excluded", v).apply()

    /** Aplikacje, od których przyszło już jakieś powiadomienie (do listy wykluczeń). */
    var seenPackages: Set<String>
        get() = prefs.getStringSet("seen", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("seen", v).apply()

    private val DEFAULT_EXCLUDED = setOf(
        "android",
        "com.android.systemui",
        "io.hammerhead.companion", // Companion Hammerhead sam przesyła swoje rzeczy do Karoo
    )
}
