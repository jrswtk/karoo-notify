package pl.karoonotify.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Usługa pierwszoplanowa: serwer GATT + reklamowanie BLE.
 * Karoo łączy się jako klient i subskrybuje charakterystykę z powiadomieniami.
 */
@SuppressLint("MissingPermission")
class BridgeService : Service() {

    private class Peer(val device: BluetoothDevice) {
        @Volatile var mtu: Int = 23
        @Volatile var subscribed: Boolean = false
        @Volatile var pendingSend: CompletableDeferred<Int>? = null
    }

    private data class Outgoing(val target: String?, val json: String)

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    private val peers = ConcurrentHashMap<String, Peer>()
    private var msgId = 0

    private var gattServer: BluetoothGattServer? = null
    private var eventsChar: BluetoothGattCharacteristic? = null
    private var advertising = false

    private val btManager: BluetoothManager by lazy { getSystemService(BluetoothManager::class.java)!! }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification(getString(R.string.fg_waiting))
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(FG_ID, notification)
            }
        } catch (e: Exception) {
            // Android 14+: typ connectedDevice wymaga przyznanych uprawnień Bluetooth
            Log.e(TAG, "startForeground nieudane", e)
            stopSelf()
            return
        }
        running = true
        val filter = IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(btStateReceiver, filter)
        }

        scope.launch { for (msg in outbox) deliver(msg) }
        scope.launch {
            NotificationHub.events.collect { e -> outbox.trySend(Outgoing(null, NotificationHub.eventJson(e))) }
        }
        startBle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESEND) {
            // Wymuszona ponowna synchronizacja (np. po zmianie filtrów)
            peers.values.filter { it.subscribed }.forEach {
                outbox.trySend(Outgoing(it.device.address, NotificationHub.snapshotJson(phoneName())))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!running) {
            super.onDestroy()
            return
        }
        running = false
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        stopBle()
        scope.cancel()
        dispatcher.close()
        NotificationHub.setKarooConnections(0)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- BLE

    private fun hasPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun startBle() {
        if (!hasPermissions()) {
            Log.w(TAG, "Brak uprawnień Bluetooth")
            updateNotification(getString(R.string.fg_no_permission))
            return
        }
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateNotification(getString(R.string.fg_bt_off))
            return
        }
        if (gattServer != null) return

        val server = btManager.openGattServer(this, gattCallback) ?: run {
            Log.e(TAG, "Nie udało się otworzyć serwera GATT")
            return
        }
        gattServer = server
        val service = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val ch = BluetoothGattCharacteristic(
            Protocol.EVENTS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        ch.addDescriptor(
            BluetoothGattDescriptor(
                Protocol.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(ch)
        eventsChar = ch
        server.addService(service) // reklamowanie startuje w onServiceAdded
        updateNotification(getString(R.string.fg_waiting))
    }

    private fun startAdvertising() {
        val advertiser = btManager.adapter?.bluetoothLeAdvertiser ?: return
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // flags(3) + UUID 128-bit(18) + manufacturer(2+2+4) = 29 bajtów <= 31
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
            .addManufacturerData(Protocol.MANUFACTURER_ID, Settings.phoneIdBytes)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopBle() {
        try { btManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertising = false
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        eventsChar = null
        peers.clear()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.i(TAG, "Reklamowanie BLE uruchomione")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.e(TAG, "Reklamowanie BLE nie powiodło się: $errorCode")
            updateNotification(getString(R.string.fg_adv_failed, errorCode))
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.i(TAG, "Usługa dodana: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Połączenie ${device.address}: $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                peers[device.address] = Peer(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peers.remove(device.address)?.pendingSend?.complete(-1)
            }
            refreshStatus()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            peers[device.address]?.mtu = mtu
            Log.i(TAG, "MTU ${device.address} = $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (peers[device.address]?.subscribed == true) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            if (descriptor.uuid != Protocol.CCCD_UUID) return
            val peer = peers.getOrPut(device.address) { Peer(device) }
            val enable = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
            peer.subscribed = enable
            Log.i(TAG, "Subskrypcja ${device.address}: $enable")
            if (enable) {
                // Nowe Karoo -> wyślij pełną listę
                outbox.trySend(Outgoing(device.address, NotificationHub.snapshotJson(phoneName())))
            }
            refreshStatus()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            peers[device.address]?.pendingSend?.complete(status)
        }
    }

    /** Wysyła jedną wiadomość (pociętą na ramki) do jednego lub wszystkich subskrybentów. */
    private suspend fun deliver(msg: Outgoing) {
        val server = gattServer ?: return
        val ch = eventsChar ?: return
        val targets = peers.values.filter { it.subscribed && (msg.target == null || it.device.address == msg.target) }
        if (targets.isEmpty()) return
        val payload = msg.json.toByteArray(Charsets.UTF_8)
        msgId = (msgId + 1) and 0xFF
        for (peer in targets) {
            val frames = try { Protocol.frames(msgId, payload, peer.mtu) } catch (e: Exception) { continue }
            for (frame in frames) {
                val done = CompletableDeferred<Int>()
                peer.pendingSend = done
                val started = try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        server.notifyCharacteristicChanged(peer.device, ch, false, frame) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        ch.value = frame
                        @Suppress("DEPRECATION")
                        server.notifyCharacteristicChanged(peer.device, ch, false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Błąd wysyłania", e)
                    false
                }
                if (!started) break
                val status = withTimeoutOrNull(2000) { done.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) break
            }
            peer.pendingSend = null
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        NotificationHub.markSent("$time · ${payload.size} B")
    }

    private fun refreshStatus() {
        val count = peers.values.count { it.subscribed }
        NotificationHub.setKarooConnections(count)
        updateNotification(
            if (count > 0) getString(R.string.fg_connected) else getString(R.string.fg_waiting),
        )
    }

    private fun phoneName(): String =
        android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: Build.MODEL

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> startBle()
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> {
                    stopBle()
                    refreshStatus()
                    updateNotification(getString(R.string.fg_bt_off))
                }
            }
        }
    }

    // ------------------------------------------------------- notyfikacja FG

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(FG_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL = "bridge"
        private const val FG_ID = 1
        const val ACTION_RESEND = "pl.karoonotify.phone.RESEND"

        @Volatile var running = false
            private set

        fun start(context: Context, action: String? = null) {
            val granted = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                Log.w(TAG, "Brak uprawnień Bluetooth – nie uruchamiam mostka")
                return
            }
            val i = Intent(context, BridgeService::class.java).setAction(action)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                // np. ForegroundServiceStartNotAllowedException przy starcie z tła
                Log.w(TAG, "Nie można uruchomić usługi", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
