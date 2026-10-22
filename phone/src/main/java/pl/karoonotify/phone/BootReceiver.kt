package pl.karoonotify.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Po restarcie telefonu (lub aktualizacji aplikacji) uruchamia mostek ponownie. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Settings.init(context.applicationContext)
        if (Settings.bridgeEnabled) BridgeService.start(context)
    }
}
