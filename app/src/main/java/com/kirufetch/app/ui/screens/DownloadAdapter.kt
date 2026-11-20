package com.kirufetch.app.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kirufetch.app.R
import com.kirufetch.app.data.DownloadEntity

class DownloadAdapter(
    private val onPause: (DownloadEntity) -> Unit,
    private val onResume: (DownloadEntity) -> Unit,
    private val onDelete: (DownloadEntity) -> Unit,
    private val onOpen: (DownloadEntity) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private val items = mutableListOf<DownloadEntity>()

    fun submit(list: List<DownloadEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

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

            itemView.setOnLongClickListener {
                onDelete(item)
                true
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
