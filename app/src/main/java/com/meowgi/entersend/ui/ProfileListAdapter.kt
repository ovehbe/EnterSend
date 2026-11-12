package com.meowgi.entersend.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.meowgi.entersend.R
import com.meowgi.entersend.data.ProfileStore

class ProfileListAdapter(
    private val context: Context,
    private val onUse: (ProfileStore.Meta) -> Unit,
) : BaseAdapter() {
    private var items: List<ProfileStore.Meta> = emptyList()
    private var activeId: String = ""

    fun submit(items: List<ProfileStore.Meta>, activeId: String) {
        this.items = items
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_profile, parent, false)
        val meta = items[position]
        val active = meta.id == activeId
        v.findViewById<TextView>(R.id.profile_name).text = meta.name
        v.findViewById<TextView>(R.id.profile_meta).text =
            if (active) context.getString(R.string.profile_active_badge) else meta.id
        val use = v.findViewById<MaterialButton>(R.id.profile_switch)
        use.isEnabled = !active
        use.alpha = if (active) 0.4f else 1f
        use.setOnClickListener { onUse(meta) }
        (v as? MaterialCardView)?.strokeColor =
            context.getColor(if (active) R.color.md_theme_primary else R.color.md_theme_outline_variant)
        return v
    }
}
