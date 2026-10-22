package pl.karoonotify.phone

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PhoneNotification(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val ts: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", key)
        .put("pkg", pkg)
        .put("app", app)
        .put("title", title)
        .put("text", text)
        .put("ts", ts)
}

sealed class HubEvent {
    data class Added(val n: PhoneNotification) : HubEvent()
    data class Removed(val key: String) : HubEvent()
}

/** Wspólny stan w procesie: łączy NotificationCatcher z BridgeService. */
object NotificationHub {
    private const val MAX_ITEMS = 20

    private val items = LinkedHashMap<String, PhoneNotification>()
    private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _karooConnections = MutableStateFlow(0)
    val karooConnections: StateFlow<Int> = _karooConnections.asStateFlow()

    private val _lastSent = MutableStateFlow<String?>(null)
    val lastSent: StateFlow<String?> = _lastSent.asStateFlow()

    fun setListenerConnected(v: Boolean) { _listenerConnected.value = v }
    fun setKarooConnections(v: Int) { _karooConnections.value = v }
    fun markSent(summary: String) { _lastSent.value = summary }

    @Synchronized
    fun replaceAll(list: List<PhoneNotification>) {
        items.clear()
        list.sortedBy { it.ts }.takeLast(MAX_ITEMS).forEach { items[it.key] = it }
    }

    @Synchronized
    fun add(n: PhoneNotification) {
        val old = items[n.key]
        // Aplikacje często ponownie wysyłają identyczne powiadomienie – nie spamujmy Karoo.
        if (old != null && old.title == n.title && old.text == n.text) return
        items.remove(n.key)
        items[n.key] = n
        while (items.size > MAX_ITEMS) items.remove(items.keys.first())
        _events.tryEmit(HubEvent.Added(n))
    }

    @Synchronized
    fun remove(key: String) {
        if (items.remove(key) != null) _events.tryEmit(HubEvent.Removed(key))
    }

    @Synchronized
    fun snapshot(): List<PhoneNotification> = items.values.toList()

    fun snapshotJson(phoneName: String): String = JSONObject()
        .put("t", "snap")
        .put("phone", phoneName)
        .put("items", JSONArray().apply { snapshot().forEach { put(it.toJson()) } })
        .toString()

    fun eventJson(e: HubEvent): String = when (e) {
        is HubEvent.Added -> JSONObject().put("t", "add").put("item", e.n.toJson()).toString()
        is HubEvent.Removed -> JSONObject().put("t", "del").put("k", e.key).toString()
    }
}
