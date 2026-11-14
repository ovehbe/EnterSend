package com.meowgi.entersend.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.meowgi.entersend.R
import com.meowgi.entersend.core.SystemAppPolicy
import com.meowgi.entersend.data.LastUsedPrefs
import java.util.Locale

/**
 * Settings → Exclude from recent: user apps with checkboxes. System / own package omitted.
 */
class ExcludeRecentActivity : AppCompatActivity() {
    private val policy by lazy { SystemAppPolicy(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exclude_recent)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val entries = loadUserApps()
        val empty = findViewById<TextView>(R.id.exclude_empty)
        val list = findViewById<ListView>(R.id.exclude_list)
        if (entries.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        val excluded = LastUsedPrefs.excluded(this).toMutableSet()
        list.adapter = Adapter(entries, excluded) { pkg, checked ->
            LastUsedPrefs.setExcluded(this, pkg, checked)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            val next = entry.pkg !in LastUsedPrefs.excluded(this)
            LastUsedPrefs.setExcluded(this, entry.pkg, next)
            (list.adapter as Adapter).refreshExcluded(LastUsedPrefs.excluded(this))
        }
    }

    private fun loadUserApps(): List<Entry> {
        val pm = packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).map { it.activityInfo.packageName }.distinct()
        return launchable
            .filter { it != packageName && !policy.isSystemApp(it) }
            .map { pkg ->
                val info = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                Entry(
                    pkg = pkg,
                    label = info?.loadLabel(pm)?.toString() ?: pkg,
                    icon = info?.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private data class Entry(val pkg: String, val label: String, val icon: Drawable?)

    private class Adapter(
        private val entries: List<Entry>,
        private var excluded: Set<String>,
        private val onToggle: (String, Boolean) -> Unit,
    ) : BaseAdapter() {
        fun refreshExcluded(next: Set<String>) {
            excluded = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Entry = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_exclude_recent, parent, false)
            val entry = entries[position]
            view.findViewById<ImageView>(R.id.exclude_icon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.exclude_label).text = entry.label
            val check = view.findViewById<CheckBox>(R.id.exclude_check)
            check.setOnCheckedChangeListener(null)
            check.isChecked = entry.pkg in excluded
            check.setOnCheckedChangeListener { _, isChecked ->
                onToggle(entry.pkg, isChecked)
                excluded = if (isChecked) excluded + entry.pkg else excluded - entry.pkg
            }
            return view
        }
    }
}
