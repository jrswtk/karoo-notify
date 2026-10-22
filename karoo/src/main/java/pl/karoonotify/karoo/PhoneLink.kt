package pl.karoonotify.karoo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Klient BLE po stronie Karoo: szuka telefonu z aplikacją Karoo Notify,
 * łączy się, subskrybuje powiadomienia i składa ramki w wiadomości JSON.
 * Po rozłączeniu automatycznie szuka telefonu ponownie.
 */
@SuppressLint("MissingPermission")
class PhoneLink(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val btManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private suspend fun runLoop() {
        var idleScans = 0
        while (scope.isActive) {
            if (!hasPermissions()) {
                KarooStore.setLink(LinkState.NO_PERMISSION)
                delay(5_000)
                continue
            }
            val adapter = btManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                KarooStore.setLink(LinkState.BT_OFF)
                delay(5_000)
                continue
            }

            KarooStore.setLink(LinkState.SEARCHING)
            val found = try {
                withTimeoutOrNull(20_000) { scanForPhone() }
            } catch (e: SecurityException) {
                null
            }
            if (found == null) {
                // Nie ma telefonu w pobliżu – szukamy rzadziej, żeby oszczędzać baterię
                idleScans++
                delay(if (idleScans < 5) 5_000L else 20_000L)
                continue
            }
            idleScans = 0
            val (device, phoneId) = found
            Log.i(TAG, "Znaleziono telefon $phoneId (${device.address})")
            KarooStore.setLink(LinkState.CONNECTING)
            try {
                connectAndListen(device, phoneId)
            } catch (e: Exception) {
                Log.w(TAG, "Błąd połączenia", e)
            }
            KarooStore.setLink(LinkState.SEARCHING)
            delay(2_000)
        }
    }

    /** Skanuje do momentu znalezienia (sparowanego) telefonu. */
    private suspend fun scanForPhone(): Pair<BluetoothDevice, String>? =
        suspendCancellableCoroutine { cont ->
            val scanner = btManager?.adapter?.bluetoothLeScanner
            if (scanner == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val idBytes = result.scanRecord?.getManufacturerSpecificData(Protocol.MANUFACTURER_ID)
                    val id = idBytes?.joinToString("") { "%02X".format(it) } ?: ""
                    val paired = KarooSettings.pairedPhoneId
                    if (paired.isNotEmpty() && id != paired) return
                    if (cont.isActive) {
                        try { scanner.stopScan(this) } catch (_: Exception) {}
                        cont.resume(result.device to id)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Skanowanie nieudane: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            }
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build())
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(filters, settings, callback)
            cont.invokeOnCancellation {
                try { scanner.stopScan(callback) } catch (_: Exception) {}
            }
        }

    /** Łączy się i blokuje do rozłączenia. */
    private suspend fun connectAndListen(device: BluetoothDevice, phoneId: String) {
        val subscribed = CompletableDeferred<Boolean>()
        val disconnected = CompletableDeferred<Unit>()
        val assembler = FrameAssembler { json -> KarooStore.handleMessage(json) }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "Stan połączenia: $newState (status $status)")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (!gatt.requestMtu(247)) gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        subscribed.complete(false)
                        disconnected.complete(Unit)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "MTU = $mtu")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val ch = gatt.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.EVENTS_UUID)
                val cccd = ch?.getDescriptor(Protocol.CCCD_UUID)
                if (ch == null || cccd == null) {
                    Log.w(TAG, "Brak usługi Karoo Notify na telefonie")
                    gatt.disconnect()
                    return
                }
                gatt.setCharacteristicNotification(ch, true)
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(cccd, value)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                subscribed.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            // Android 13+
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
            ) {
                if (characteristic.uuid == Protocol.EVENTS_UUID) assembler.accept(value)
            }

            // Android 12 i starsze
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                if (characteristic.uuid == Protocol.EVENTS_UUID) assembler.accept(value)
            }
        }

        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return
        try {
            val ok = withTimeoutOrNull(30_000) { subscribed.await() } ?: false
            if (!ok) {
                Log.w(TAG, "Nie udało się zasubskrybować")
                return
            }
            if (KarooSettings.pairedPhoneId.isEmpty() && phoneId.isNotEmpty()) {
                KarooSettings.pairedPhoneId = phoneId // pierwsze połączenie = parowanie
            }
            KarooStore.setLink(LinkState.CONNECTED)
            disconnected.await()
        } finally {
            try { gatt.disconnect() } catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
        }
    }

    /** Składa ramki [msgId][index][count][dane] w kompletne wiadomości. */
    private class FrameAssembler(private val onMessage: (String) -> Unit) {
        private var currentId = -1
        private var expected = 0
        private val parts = HashMap<Int, ByteArray>()

        @Synchronized
        fun accept(frame: ByteArray) {
            if (frame.size < Protocol.HEADER_SIZE) return
            val id = frame[0].toInt() and 0xFF
            val index = frame[1].toInt() and 0xFF
            val count = frame[2].toInt() and 0xFF
            if (id != currentId || index == 0) {
                currentId = id
                expected = count
                parts.clear()
            }
            parts[index] = frame.copyOfRange(Protocol.HEADER_SIZE, frame.size)
            if (expected > 0 && parts.size == expected) {
                val total = (0 until expected).mapNotNull { parts[it] }
                parts.clear()
                currentId = -1
                if (total.size != expected) return
                val bytes = total.fold(ByteArray(0)) { acc, b -> acc + b }
                try {
                    onMessage(String(bytes, Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.w(TAG, "Błędna wiadomość", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhoneLink"
    }
}
