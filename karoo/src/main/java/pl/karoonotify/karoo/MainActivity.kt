package pl.karoonotify.karoo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

/** Ekran ustawień na Karoo (Aplikacje → Karoo Notify). */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var link: TextView
    private lateinit var paired: TextView
    private lateinit var items: TextView

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KarooSettings.init(this)
        setContentView(R.layout.activity_main)
        link = findViewById(R.id.link)
        paired = findViewById(R.id.paired)
        items = findViewById(R.id.items)

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestPermissions(permissions, 1) }
        findViewById<Button>(R.id.btnClear).setOnClickListener { KarooStore.clear() }
        findViewById<Button>(R.id.btnForget).setOnClickListener { KarooSettings.pairedPhoneId = "" }
        findViewById<Switch>(R.id.swPopup).apply {
            isChecked = KarooSettings.popupEnabled
            setOnCheckedChangeListener { _, c -> KarooSettings.popupEnabled = c }
        }
        findViewById<Switch>(R.id.swRemove).apply {
            isChecked = KarooSettings.removeDismissed
            setOnCheckedChangeListener { _, c -> KarooSettings.removeDismissed = c }
        }

        if (!hasPermissions()) requestPermissions(permissions, 1)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun hasPermissions() = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun refresh() {
        val s = KarooStore.state.value
        link.text = when (s.link) {
            LinkState.CONNECTED -> "✅ " + getString(R.string.link_connected, s.phoneName ?: "telefon")
            LinkState.SEARCHING -> "⏳ " + getString(R.string.link_searching)
            LinkState.CONNECTING -> "⏳ " + getString(R.string.link_connecting)
            LinkState.BT_OFF -> "❌ " + getString(R.string.link_bt_off)
            LinkState.NO_PERMISSION -> "❌ " + getString(R.string.link_no_permission)
        }
        val id = KarooSettings.pairedPhoneId
        paired.text = if (id.isEmpty()) getString(R.string.paired_none) else getString(R.string.paired, id)
        findViewById<Button>(R.id.btnPermissions).visibility =
            if (hasPermissions()) android.view.View.GONE else android.view.View.VISIBLE
        items.text = s.items.take(10).joinToString("\n\n") { "${it.app}\n${it.title}\n${it.text}".trim() }
            .ifEmpty { getString(R.string.no_notifications) }
    }
}
