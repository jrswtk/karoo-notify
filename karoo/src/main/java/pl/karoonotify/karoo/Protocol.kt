package pl.karoonotify.karoo

import java.util.UUID

/**
 * Wspólny protokół telefon <-> Karoo (ta sama kopia jest w module phone).
 *
 * Telefon jest serwerem GATT i reklamuje SERVICE_UUID, a w danych producenta
 * (company id 0xFFFF) wysyła 4-bajtowy identyfikator telefonu.
 *
 * Karoo subskrybuje EVENTS_UUID (notify). Każda wiadomość to JSON w UTF-8,
 * pocięty na ramki: [msgId][index][count][payload...].
 *
 * Wiadomości:
 *   {"t":"snap","phone":"Pixel 10 Pro","items":[item...]}   - pełna lista po podłączeniu
 *   {"t":"add","item":item}                                 - nowe/zaktualizowane powiadomienie
 *   {"t":"del","k":"key"}                                    - powiadomienie usunięte na telefonie
 *   {"t":"ping"}                                             - podtrzymanie połączenia
 * item = {"k":key,"pkg":pakiet,"app":nazwa aplikacji,"title":..,"text":..,"ts":epochMillis}
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("6b4e0001-7c3a-4f2e-9a51-3d2b8c1f0a77")
    val EVENTS_UUID: UUID = UUID.fromString("6b4e0002-7c3a-4f2e-9a51-3d2b8c1f0a77")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MANUFACTURER_ID = 0xFFFF
    const val HEADER_SIZE = 3

    fun frames(msgId: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        // 3 bajty narzutu ATT + 3 bajty naszego nagłówka
        val chunk = (mtu - 3 - HEADER_SIZE).coerceAtLeast(17)
        val count = ((payload.size + chunk - 1) / chunk).coerceAtLeast(1)
        require(count <= 255) { "Wiadomość za duża" }
        return (0 until count).map { i ->
            val start = i * chunk
            val end = minOf(start + chunk, payload.size)
            val out = ByteArray(HEADER_SIZE + (end - start))
            out[0] = msgId.toByte()
            out[1] = i.toByte()
            out[2] = count.toByte()
            System.arraycopy(payload, start, out, HEADER_SIZE, end - start)
            out
        }
    }
}
