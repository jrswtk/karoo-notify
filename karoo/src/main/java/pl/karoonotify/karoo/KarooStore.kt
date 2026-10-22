package pl.karoonotify.karoo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class Item(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val ts: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = Item(
            key = o.optString("k"),
            app = o.optString("app"),
            title = o.optString("title"),
            text = o.optString("text"),
            ts = o.optLong("ts", System.currentTimeMillis()),
        )
    }
}

enum class LinkState { NO_PERMISSION, BT_OFF, SEARCHING, CONNECTING, CONNECTED }

data class KarooState(
    val items: List<Item> = emptyList(),
    val link: LinkState = LinkState.SEARCHING,
    val phoneName: String? = null,
    val lastMessageAt: Long? = null,
)

/** Stan w procesie aplikacji na Karoo (rozszerzenie, pole danych i ekran ustawień). */
object KarooStore {
    private const val MAX_ITEMS = 30

    private val _state = MutableStateFlow(KarooState())
    val state: StateFlow<KarooState> = _state.asStateFlow()

    /** Tylko naprawdę nowe powiadomienia (nie z synchronizacji) – do banerów. */
    private val _incoming = MutableSharedFlow<Item>(extraBufferCapacity = 16)
    val incoming: SharedFlow<Item> = _incoming.asSharedFlow()

    fun setLink(link: LinkState, phoneName: String? = _state.value.phoneName) =
        _state.update { it.copy(link = link, phoneName = phoneName) }

    fun handleMessage(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        when (o.optString("t")) {
            "snap" -> {
                val arr = o.optJSONArray("items")
                val fresh = (0 until (arr?.length() ?: 0)).map { Item.fromJson(arr!!.getJSONObject(it)) }
                _state.update { s ->
                    val merged = if (KarooSettings.removeDismissed) {
                        fresh
                    } else {
                        (s.items.filter { old -> fresh.none { it.key == old.key } } + fresh)
                    }
                    s.copy(
                        items = merged.sortedByDescending { it.ts }.take(MAX_ITEMS),
                        phoneName = o.optString("phone").ifBlank { s.phoneName },
                        lastMessageAt = now,
                    )
                }
            }
            "add" -> {
                val item = Item.fromJson(o.optJSONObject("item") ?: return)
                _state.update { s ->
                    s.copy(
                        items = (listOf(item) + s.items.filter { it.key != item.key }).take(MAX_ITEMS),
                        lastMessageAt = now,
                    )
                }
                _incoming.tryEmit(item)
            }
            "del" -> {
                val key = o.optString("k")
                _state.update { s ->
                    s.copy(
                        items = if (KarooSettings.removeDismissed) s.items.filter { it.key != key } else s.items,
                        lastMessageAt = now,
                    )
                }
            }
            else -> _state.update { it.copy(lastMessageAt = now) }
        }
    }

    fun clear() = _state.update { it.copy(items = emptyList()) }
}
