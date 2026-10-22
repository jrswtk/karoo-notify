package pl.karoonotify.phone

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Wybór aplikacji, których powiadomienia trafiają na Karoo.
 * Dwa tryby: wszystkie oprócz odznaczonych / tylko zaznaczone.
 */
class AppsActivity : Activity() {

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable?, val seen: Boolean)

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query = ""
    private var changed = false

    private lateinit var adapter: AppsAdapter
    private lateinit var summary: TextView
    private val loader = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        summary = findViewById(R.id.summary)
        adapter = AppsAdapter()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val app = shown[position]
            Settings.setAppEnabled(app.pkg, !Settings.isAppEnabled(app.pkg))
            changed = true
            adapter.notifyDataSetChanged()
            updateSummary()
        }

        val mode = findViewById<RadioGroup>(R.id.mode)
        mode.check(if (Settings.onlySelectedApps) R.id.modeSelected else R.id.modeAll)
        mode.setOnCheckedChangeListener { _, id ->
            Settings.onlySelectedApps = id == R.id.modeSelected
            changed = true
            adapter.notifyDataSetChanged()
            updateSummary()
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })

        findViewById<View>(R.id.btnAllOn).setOnClickListener { setAllShown(true) }
        findViewById<View>(R.id.btnAllOff).setOnClickListener { setAllShown(false) }

        loadApps()
    }

    override fun onPause() {
        super.onPause()
        if (changed) {
            changed = false
            NotificationCatcher.instance?.resync()
            if (BridgeService.running) BridgeService.start(this, BridgeService.ACTION_RESEND)
        }
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    /** Włącza/wyłącza wszystkie aktualnie widoczne (np. wyszukane) aplikacje. */
    private fun setAllShown(enabled: Boolean) {
        shown.forEach { Settings.setAppEnabled(it.pkg, enabled) }
        changed = true
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun loadApps() {
        val progress = findViewById<ProgressBar>(R.id.progress)
        loader.execute {
            val pm = packageManager
            val seen = Settings.seenPackages
            // Wszystko, co ma ikonę w szufladzie aplikacji + to, co już wysłało powiadomienie
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            ).map { it.activityInfo.packageName }
            val packages = (launchable + seen).toSet() - packageName
            val entries = packages.mapNotNull { pkg ->
                try {
                    val info: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
                    AppEntry(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), pkg in seen)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }.sortedWith(compareByDescending<AppEntry> { it.seen }.thenBy { it.label.lowercase() })

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                all = entries
                progress.visibility = View.GONE
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.lowercase()
        shown = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || it.pkg.contains(q) }
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun updateSummary() {
        if (all.isEmpty()) return
        val enabled = all.count { Settings.isAppEnabled(it.pkg) }
        summary.text = getString(
            if (Settings.onlySelectedApps) R.string.summary_selected else R.string.summary_all,
            enabled, all.size,
        )
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.label).text = app.label
            view.findViewById<TextView>(R.id.sub).text =
                if (app.seen) getString(R.string.app_seen) else app.pkg
            view.findViewById<Switch>(R.id.toggle).isChecked = Settings.isAppEnabled(app.pkg)
            return view
        }
    }
}
