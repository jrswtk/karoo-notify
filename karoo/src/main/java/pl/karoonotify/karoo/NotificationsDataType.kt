package pl.karoonotify.karoo

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pole graficzne "Powiadomienia" do ekranu jazdy.
 * Dopasowuje się do rozmiaru pola: małe pole = ostatnie powiadomienie,
 * większe pole = lista tylu powiadomień, ile się zmieści.
 */
class NotificationsDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    /** Liczba powiadomień jako wartość liczbowa (gdyby ktoś chciał jej użyć gdzie indziej). */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            KarooStore.state.collect { s ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to s.items.size.toDouble())),
                    ),
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            // Własny nagłówek zamiast standardowego
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            if (config.preview) {
                emitter.updateView(render(context, config, previewState()))
                return@launch
            }
            // Odświeżaj przy każdej zmianie i co 30 s (żeby "x min temu" było aktualne)
            val ticker = flow {
                while (true) {
                    emit(Unit)
                    delay(30_000)
                }
            }
            combine(KarooStore.state, ticker) { s, _ -> s }
                .conflate()
                .collect { s ->
                    emitter.updateView(render(context, config, s))
                    delay(1_000) // Karoo przyjmuje maks. 1 aktualizację widoku na sekundę
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun render(context: Context, config: ViewConfig, state: KarooState): RemoteViews {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val heightDp = config.viewSize.second / density
        return if (heightDp < COMPACT_MAX_DP) {
            renderCompact(context, state)
        } else {
            val rows = ((heightDp - HEADER_DP) / ROW_DP).toInt().coerceAtLeast(1)
            renderList(context, state, rows)
        }
    }

    private fun renderCompact(context: Context, state: KarooState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.field_compact)
        val item = state.items.firstOrNull()
        if (item == null) {
            views.setTextViewText(R.id.compactMeta, statusText(context, state))
            views.setTextViewText(R.id.compactBody, context.getString(R.string.no_notifications))
            return views
        }
        val more = state.items.size - 1
        val meta = buildString {
            append(item.app)
            append(" · ")
            append(relativeTime(context, item.ts))
            if (more > 0) append("  (+$more)")
            if (state.link != LinkState.CONNECTED) append("  ⚠")
        }
        views.setTextViewText(R.id.compactMeta, meta)
        views.setTextViewText(R.id.compactBody, joinTitleText(item))
        return views
    }

    private fun renderList(context: Context, state: KarooState, maxRows: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.field_root)
        views.setTextViewText(
            R.id.headerText,
            context.getString(R.string.header_count, state.items.size),
        )
        views.setTextViewText(R.id.headerStatus, statusText(context, state))
        views.removeAllViews(R.id.list)

        if (state.items.isEmpty()) {
            views.setViewVisibility(R.id.empty, View.VISIBLE)
            return views
        }
        views.setViewVisibility(R.id.empty, View.GONE)
        // Gdy jest miejsce tylko na 1–2 wpisy, dajemy im więcej linii tekstu
        val layout = if (maxRows <= 2) R.layout.field_row_tall else R.layout.field_row
        state.items.take(maxRows).forEach { item ->
            val row = RemoteViews(context.packageName, layout)
            row.setTextViewText(R.id.rowMeta, "${item.app} · ${relativeTime(context, item.ts)}")
            row.setTextViewText(R.id.rowTitle, item.title.ifBlank { item.app })
            row.setTextViewText(R.id.rowBody, item.text)
            row.setViewVisibility(R.id.rowBody, if (item.text.isBlank()) View.GONE else View.VISIBLE)
            views.addView(R.id.list, row)
        }
        return views
    }

    private fun joinTitleText(item: Item): String = when {
        item.title.isBlank() -> item.text
        item.text.isBlank() -> item.title
        else -> "${item.title}: ${item.text}"
    }

    private fun statusText(context: Context, state: KarooState): String = when (state.link) {
        LinkState.CONNECTED -> state.phoneName ?: ""
        LinkState.SEARCHING -> context.getString(R.string.status_searching)
        LinkState.CONNECTING -> context.getString(R.string.status_connecting)
        LinkState.BT_OFF -> context.getString(R.string.status_bt_off)
        LinkState.NO_PERMISSION -> context.getString(R.string.status_no_permission)
    }

    private fun relativeTime(context: Context, ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> context.getString(R.string.time_now)
            diff < 60 * 60_000 -> context.getString(R.string.time_minutes, (diff / 60_000).toInt())
            else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }

    private fun previewState(): KarooState {
        val now = System.currentTimeMillis()
        return KarooState(
            items = listOf(
                Item("p1", "WhatsApp", "Kasia", "Gdzie jesteś? Czekamy na kawie ☕", now - 60_000),
                Item("p2", "Gmail", "Zamówienie wysłane", "Twoja paczka jest w drodze", now - 12 * 60_000),
                Item("p3", "Messenger", "Grupa Szosa", "Marek: jutro 7:00 spod Wawelu", now - 40 * 60_000),
            ),
            link = LinkState.CONNECTED,
            phoneName = "Pixel 10 Pro",
        )
    }

    companion object {
        const val TYPE_ID = "notifications"
        private const val COMPACT_MAX_DP = 110f
        private const val HEADER_DP = 22f
        private const val ROW_DP = 72f
    }
}
