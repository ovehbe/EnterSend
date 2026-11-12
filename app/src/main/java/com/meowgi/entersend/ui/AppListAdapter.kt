package com.meowgi.entersend.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.meowgi.entersend.EnterSendApp
import com.meowgi.entersend.R
import com.meowgi.entersend.data.DisplayMode
import java.util.concurrent.Executor

/**
 * Stable-id list over an immutable snapshot. Icons load lazily per bind (never at scan time)
 * to avoid OOM / jank on large catalogs.
 */
class AppListAdapter(
    private val context: Context,
    private val iconExecutor: Executor = (context.applicationContext as? EnterSendApp)?.io
        ?: java.util.concurrent.Executors.newSingleThreadExecutor(),
) : BaseAdapter() {
    /** Immutable snapshot — never mutate in place (avoids ConcurrentModification / flicker). */
    private var rows: List<AppRow> = emptyList()
    private val main = Handler(Looper.getMainLooper())

    /** Skips [notifyDataSetChanged] when the visible content is unchanged (no jank). */
    fun submit(rows: List<AppRow>) {
        val copy = rows.toList()
        if (sameContent(this.rows, copy)) return
        this.rows = copy
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = rows[position].pkg.hashCode().toLong()
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        // Guard against concurrent submit shrinking the list mid-bind.
        val snapshot = rows
        if (position < 0 || position >= snapshot.size) return v
        val row = snapshot[position]
        val iconView = v.findViewById<ImageView>(R.id.app_icon)
        iconView.setImageDrawable(row.icon ?: AppIconLoader.getCached(row.pkg))
        iconView.tag = row.pkg
        if (row.icon == null && AppIconLoader.getCached(row.pkg) == null) {
            AppIconLoader.loadAsync(context, row.pkg, iconExecutor) { drawable ->
                main.post {
                    if (iconView.tag == row.pkg) {
                        iconView.setImageDrawable(drawable)
                    }
                }
            }
        }

        v.findViewById<TextView>(R.id.app_name).text = row.label

        val mode = context.getString(
            when (row.displayMode) {
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

    companion object {
        fun sameContent(a: List<AppRow>, b: List<AppRow>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x.pkg != y.pkg || x.label != y.label || x.mode != y.mode ||
                    x.displayMode != y.displayMode || x.health != y.health ||
                    x.needsRecheck != y.needsRecheck || x.needsTeaching != y.needsTeaching ||
                    x.rule.successCount != y.rule.successCount ||
                    x.rule.failureCount != y.rule.failureCount
                ) {
                    return false
                }
            }
            return true
        }
    }
}
