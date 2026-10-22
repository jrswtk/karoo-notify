package pl.karoonotify.karoo

import android.content.Intent
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RequestBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NotifyExtension : KarooExtension(EXTENSION_ID, "1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var phoneLink: PhoneLink
    private var alertJob: Job? = null

    override val types by lazy {
        listOf(NotificationsDataType(extension))
    }

    override fun onCreate() {
        super.onCreate()
        KarooSettings.init(this)
        karooSystem = KarooSystemService(this)
        phoneLink = PhoneLink(applicationContext)

        karooSystem.connect { connected ->
            if (connected) {
                // Poproś Karoo o włączony Bluetooth dla tego rozszerzenia
                karooSystem.dispatch(RequestBluetooth(extension))
            }
        }
        phoneLink.start()

        alertJob = CoroutineScope(Dispatchers.IO).launch {
            KarooStore.incoming.collect { item ->
                if (!KarooSettings.popupEnabled || !karooSystem.connected) return@collect
                // Nie pokazuj banera dla starych powiadomień, które tylko się zaktualizowały
                if (System.currentTimeMillis() - item.ts > 2 * 60_000) return@collect
                karooSystem.dispatch(
                    InRideAlert(
                        id = "notify-${item.key.hashCode()}",
                        icon = R.drawable.ic_bell,
                        title = listOf(item.app, item.title).filter { it.isNotBlank() }.joinToString(": "),
                        detail = item.text.take(140),
                        autoDismissMs = 8_000,
                        backgroundColor = R.color.alert_bg,
                        textColor = R.color.alert_text,
                    ),
                )
            }
        }
    }

    override fun onBonusAction(actionId: String) {
        when (actionId) {
            "clear" -> KarooStore.clear()
            "open" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onDestroy() {
        alertJob?.cancel()
        phoneLink.stop()
        try { karooSystem.dispatch(io.hammerhead.karooext.models.ReleaseBluetooth(extension)) } catch (_: Exception) {}
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "karoo-notify"
    }
}
