package com.kirufetch.app.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kirufetch.app.R
import com.kirufetch.app.data.DownloadEntity
import com.kirufetch.app.ui.widget.VideoThumbs

class DownloadAdapter(
    private val onPause: (DownloadEntity) -> Unit,
    private val onResume: (DownloadEntity) -> Unit,
    private val onOpen: (DownloadEntity) -> Unit,
    private val onLongPress: (DownloadEntity) -> Unit,
    private val onToggleSelect: (DownloadEntity) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private val items = mutableListOf<DownloadEntity>()

    var selectionMode = false
        private set
    private val selected = linkedSetOf<String>()

    fun submit(list: List<DownloadEntity>) {
        items.clear()
        items.addAll(list)
        val alive = items.map { it.id }.toSet()
        selected.retainAll(alive)
        notifyDataSetChanged()
    }

    fun enterSelection(id: String) {
        selectionMode = true
        selected.clear()
        selected.add(id)
        notifyDataSetChanged()
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
    }

    fun toggle(id: String) {
        if (!selected.remove(id)) selected.add(id)
        notifyDataSetChanged()
    }

    fun selectAll(ids: List<String>) {
        selected.clear()
        selected.addAll(ids)
        notifyDataSetChanged()
    }

    fun selectedIds(): List<String> = selected.toList()

    fun selectedCount(): Int = selected.size

    fun allSelected(ids: List<String>): Boolean = ids.isNotEmpty() && selected.containsAll(ids)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.download_name)
        private val status: TextView = itemView.findViewById(R.id.download_status)
        private val bar: ProgressBar = itemView.findViewById(R.id.download_progress)
        private val action: ImageButton = itemView.findViewById(R.id.download_action)
        private val thumb: ImageView = itemView.findViewById(R.id.download_thumb)
        private val ctx = itemView.context

        fun bind(item: DownloadEntity) {
            name.text = item.fileName

            status.text = when {
                item.isFailed -> ctx.getString(R.string.status_failed, item.error ?: "")
                item.isDone -> ctx.getString(R.string.status_done, formatSize(item.totalBytes))
                item.isPaused -> ctx.getString(R.string.status_paused, item.progress)
                item.isRunning -> ctx.getString(R.string.status_running, item.progress)
                item.status == DownloadEntity.STATUS_CANCELED -> ctx.getString(R.string.status_canceled)
                else -> ctx.getString(R.string.status_queued)
            }

            val showBar = item.isRunning || item.isPaused
            bar.visibility = if (showBar) View.VISIBLE else View.GONE
            bar.progress = item.progress

            if (item.isDone) {
                action.setImageResource(R.drawable.ic_play_circle)
                action.contentDescription = ctx.getString(R.string.play)
                action.setOnClickListener { onOpen(item) }
            } else {
                action.setImageResource(
                    if (item.isPaused) R.drawable.ic_play else R.drawable.ic_pause
                )
                action.setOnClickListener {
                    if (item.isPaused) onResume(item) else onPause(item)
                }
            }

            bindThumb(item)

            val chosen = selectionMode && selected.contains(item.id)
            itemView.setBackgroundResource(
                if (chosen) R.drawable.bg_card_selected else R.drawable.bg_card
            )
            action.visibility = if (selectionMode) View.INVISIBLE else View.VISIBLE

            itemView.setOnLongClickListener {
                onLongPress(item)
                true
            }
            itemView.setOnClickListener {
                if (selectionMode) onToggleSelect(item)
            }
        }

        private fun bindThumb(item: DownloadEntity) {
            val tagKey = item.id
            thumb.tag = tagKey
            if (!item.isDone) {
                thumb.setImageDrawable(null)
                return
            }
            VideoThumbs.load(ctx, tagKey, item.outputPath) { bmp ->
                if (thumb.tag == tagKey) thumb.setImageBitmap(bmp)
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return ctx.getString(R.string.size_unknown)
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(kb)
        }
    }
}
