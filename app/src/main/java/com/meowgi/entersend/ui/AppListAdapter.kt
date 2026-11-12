package com.meowgi.entersend.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.meowgi.entersend.R
import com.meowgi.entersend.data.DisplayMode

class AppListAdapter(private val context: Context) : BaseAdapter() {
    private var rows: List<AppRow> = emptyList()

    fun submit(rows: List<AppRow>) {
        this.rows = rows
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val row = rows[position]
        v.findViewById<ImageView>(R.id.app_icon).setImageDrawable(row.icon)
        v.findViewById<TextView>(R.id.app_name).text = row.label

        val mode = context.getString(
            when (row.rule.displayMode) {
                DisplayMode.NEVER -> R.string.mode_never
                DisplayMode.OFF -> R.string.mode_off
                DisplayMode.PASS_THROUGH -> R.string.mode_pass_through
                DisplayMode.BUTTON -> R.string.mode_button
                DisplayMode.TAP_SPOT -> R.string.mode_tap_spot
                DisplayMode.AUTO -> R.string.mode_auto
            },
        )
        val notes = listOfNotNull(
            mode,
            context.getString(R.string.needs_recheck).takeIf { row.needsRecheck },
            context.getString(R.string.needs_teaching).takeIf { row.needsTeaching && !row.needsRecheck },
        )
        v.findViewById<TextView>(R.id.app_status).text = notes.joinToString(" · ")

        val (color, desc) = when (row.health) {
            Health.NONE -> R.color.health_none to R.string.health_none
            Health.GOOD -> R.color.health_good to R.string.health_good
            Health.MIXED -> R.color.health_mixed to R.string.health_mixed
            Health.BAD -> R.color.health_bad to R.string.health_bad
        }
        v.findViewById<View>(R.id.app_health).apply {
            background.mutate().setTint(context.getColor(color))
            contentDescription = context.getString(desc, row.rule.successCount, row.rule.failureCount)
        }
        return v
    }
}
