package pl.karoonotify.phone

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Odbiera WSZYSTKIE powiadomienia z telefonu (wymaga "Dostępu do powiadomień"),
 * filtruje je i przekazuje do NotificationHub.
 */
class NotificationCatcher : NotificationListenerService() {

    private val labelCache = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        instance = this
        NotificationHub.setListenerConnected(true)
        resync()
        if (Settings.bridgeEnabled) BridgeService.start(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        NotificationHub.setListenerConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        convert(sbn)?.let { NotificationHub.add(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationHub.remove(sbn.key)
    }

    /** Odbudowuje listę z aktualnie widocznych powiadomień (np. po zmianie filtrów). */
    fun resync() {
        val active = try { activeNotifications?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        NotificationHub.replaceAll(active.mapNotNull { convert(it) })
    }

    private fun convert(sbn: StatusBarNotification): PhoneNotification? {
        val pkg = sbn.packageName
        if (pkg == packageName) return null
        val n = sbn.notification ?: return null

        rememberSeen(pkg)
        if (!Settings.isAppEnabled(pkg)) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (Settings.skipOngoing && (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) return null
        if (Settings.skipSilent && isSilent(sbn)) return null

        val extras = n.extras
        var title = (
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            )?.toString()?.trim().orEmpty()

        var text = ""
        // Komunikatory (WhatsApp, Messenger, SMS, Signal...) – bierzemy ostatnią wiadomość
        val messaging = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: Exception) { null }
        val last = messaging?.messages?.lastOrNull()
        if (last != null && !last.text.isNullOrBlank()) {
            val sender = last.person?.name?.toString()
            text = last.text.toString()
            if (title.isEmpty()) {
                title = sender.orEmpty()
            } else if (!sender.isNullOrBlank() && sender != title && messaging?.isGroupConversation == true) {
                text = "$sender: $text"
            }
        }
        if (text.isEmpty()) {
            text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                    ?: n.tickerText
                )?.toString()?.trim().orEmpty()
        }
        if (title.isEmpty() && text.isEmpty()) return null

        return PhoneNotification(
            key = sbn.key,
            pkg = pkg,
            app = appLabel(pkg),
            title = title.take(80),
            text = text.replace(Regex("\\s+"), " ").take(240),
            ts = sbn.postTime,
        )
    }

    private fun isSilent(sbn: StatusBarNotification): Boolean = try {
        val r = Ranking()
        currentRanking.getRanking(sbn.key, r) && r.importance < NotificationManager.IMPORTANCE_DEFAULT
    } catch (e: Exception) { false }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) { pkg }
    }

    private fun rememberSeen(pkg: String) {
        val seen = Settings.seenPackages
        if (pkg !in seen) Settings.seenPackages = seen + pkg
    }

    companion object {
        private const val TAG = "NotificationCatcher"
        @Volatile var instance: NotificationCatcher? = null
    }
}
