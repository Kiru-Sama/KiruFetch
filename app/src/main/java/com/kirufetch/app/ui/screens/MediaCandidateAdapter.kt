package com.kirufetch.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.kirufetch.app.R
import com.kirufetch.app.sniff.MediaCandidate
import com.kirufetch.app.ui.widget.RemoteThumbs

class MediaCandidateAdapter(
    private val onClick: (MediaCandidate) -> Unit
) : RecyclerView.Adapter<MediaCandidateAdapter.VH>() {

    private val items = mutableListOf<MediaCandidate>()

    fun submit(list: List<MediaCandidate>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_candidate, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val kind: TextView = view.findViewById(R.id.candidate_kind)
        private val title: TextView = view.findViewById(R.id.candidate_title)
        private val meta: TextView = view.findViewById(R.id.candidate_meta)
        private val download: ImageButton = view.findViewById(R.id.candidate_download)
        private val thumb: ImageView = view.findViewById(R.id.candidate_thumb)
        private val ctx: Context = view.context

        fun bind(item: MediaCandidate) {
            kind.text = item.label

            val name = item.url.substringAfterLast('/').substringBefore('?')
            title.text = name.ifBlank { item.id.take(12) }

            meta.text = buildString {
                if (!item.pageUrl.isNullOrBlank()) {
                    append(runCatching { java.net.URL(item.pageUrl).host }.getOrDefault(""))
                }
                if (item.mimeType != null) {
                    if (isNotEmpty()) append(" · ")
                    append(item.mimeType)
                }
            }.ifBlank { item.url.take(48) }

            bindThumb(item)

            download.setOnClickListener { onClick(item) }
            itemView.setOnClickListener { onClick(item) }

            itemView.setOnLongClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("video url", item.url))
                Toast.makeText(ctx, R.string.copy_link, Toast.LENGTH_SHORT).show()
                true
            }
        }

        private fun bindThumb(item: MediaCandidate) {
            val poster = item.posterUrl
            thumb.tag = item.id
            thumb.setImageDrawable(null)
            if (poster.isNullOrBlank()) return
            RemoteThumbs.load(ctx, poster) { bmp ->
                if (thumb.tag == item.id && bmp != null) thumb.setImageBitmap(bmp)
            }
        }
    }
}
