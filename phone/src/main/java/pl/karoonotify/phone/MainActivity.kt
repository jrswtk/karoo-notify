package pl.karoonotify.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        findViewById<TextView>(R.id.phoneId).text = getString(R.string.phone_id, Settings.phoneId)

        findViewById<Button>(R.id.btnListener).setOnClickListener { openListenerSettings() }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestRuntimePermissions() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnExcluded).setOnClickListener { showExcludedDialog() }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            NotificationHub.add(
                PhoneNotification(
                    key = "test-${System.currentTimeMillis()}",
                    pkg = packageName,
                    app = getString(R.string.app_name),
                    title = getString(R.string.test_title),
                    text = getString(R.string.test_text),
                    ts = System.currentTimeMillis(),
                ),
            )
        }

        findViewById<Switch>(R.id.swBridge).apply {
            isChecked = Settings.bridgeEnabled
            setOnCheckedChangeListener { _, checked ->
                Settings.bridgeEnabled = checked
                if (checked) BridgeService.start(this@MainActivity) else BridgeService.stop(this@MainActivity)
            }
        }
        findViewById<Switch>(R.id.swOngoing).apply {
            isChecked = Settings.skipOngoing
            setOnCheckedChangeListener { _, checked -> Settings.skipOngoing = checked; filtersChanged() }
        }
        findViewById<Switch>(R.id.swSilent).apply {
            isChecked = Settings.skipSilent
            setOnCheckedChangeListener { _, checked -> Settings.skipSilent = checked; filtersChanged() }
        }

        if (!hasRuntimePermissions()) requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.bridgeEnabled && hasRuntimePermissions() && !BridgeService.running) {
            BridgeService.start(this)
        }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasRuntimePermissions() && Settings.bridgeEnabled) {
            // Restart, żeby serwer BLE wystartował z uprawnieniami
            BridgeService.stop(this)
            BridgeService.start(this)
        }
    }

    private fun refresh() {
        val ok = "✅"
        val bad = "❌"
        val listener = isListenerEnabled()
        val perms = hasRuntimePermissions()
        val battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        val karoo = NotificationHub.karooConnections.value
        status.text = buildString {
            appendLine("${if (listener) ok else bad} ${getString(R.string.st_listener)}")
            appendLine("${if (perms) ok else bad} ${getString(R.string.st_permissions)}")
            appendLine("${if (battery) ok else "⚠️"} ${getString(R.string.st_battery)}")
            appendLine("${if (BridgeService.running) ok else bad} ${getString(R.string.st_bridge)}")
            appendLine(
                if (karoo > 0) "🚴 ${getString(R.string.st_karoo_connected, karoo)}"
                else "⏳ ${getString(R.string.st_karoo_waiting)}",
            )
            appendLine("📋 ${getString(R.string.st_items, NotificationHub.snapshot().size)}")
            NotificationHub.lastSent.value?.let { append("📤 ${getString(R.string.st_last_sent, it)}") }
        }
    }

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openListenerSettings() {
        val component = ComponentName(this, NotificationCatcher::class.java)
        val detail = Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(SysSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        try {
            startActivity(detail)
        } catch (e: Exception) {
            startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private val runtimePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun hasRuntimePermissions(): Boolean =
        runtimePermissions.take(2).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestRuntimePermissions() {
        val missing = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        requestPermissions(missing.toTypedArray(), 1)
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showExcludedDialog() {
        val pm = packageManager
        val packages = (Settings.seenPackages + Settings.excludedPackages)
            .filter { it != packageName }
            .map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) { pkg }
                pkg to label
            }
            .sortedBy { it.second.lowercase() }
        if (packages.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.excluded_empty).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val excluded = Settings.excludedPackages.toMutableSet()
        val checked = BooleanArray(packages.size) { packages[it].first in excluded }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_excluded)
            .setMultiChoiceItems(packages.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) excluded += packages[which].first else excluded -= packages[which].first
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Settings.excludedPackages = excluded
                filtersChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun filtersChanged() {
        NotificationCatcher.instance?.resync()
        if (BridgeService.running) BridgeService.start(this, BridgeService.ACTION_RESEND)
    }
}
