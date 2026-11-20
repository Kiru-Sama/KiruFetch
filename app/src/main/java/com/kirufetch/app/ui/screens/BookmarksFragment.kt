package com.kirufetch.app.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.kirufetch.app.R
import com.kirufetch.app.data.BrowserStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class BookmarksFragment : Fragment(R.layout.fragment_bookmarks) {

    private lateinit var adapter: BookmarkAdapter
    private lateinit var store: BrowserStore
    private var showingHistory = false
    private lateinit var clearButton: TextView
    private lateinit var emptyText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = BrowserStore.get(requireContext())

        // 书签页是盖在浏览器页上的（add 而非 replace），返回键要能自己收掉
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        adapter = BookmarkAdapter { url ->
            (activity as? MainActivity)?.openUrlInBrowser(url)
            parentFragmentManager.popBackStack()
        }

        val list = view.findViewById<RecyclerView>(R.id.bookmark_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        clearButton = view.findViewById(R.id.clear_history)
        emptyText = view.findViewById(R.id.bookmark_empty)

        clearButton.setOnClickListener { store.clearHistory() }

        val tabs = view.findViewById<TabLayout>(R.id.tab_layout)
        tabs.addTab(tabs.newTab().setText(R.string.bookmarks))
        tabs.addTab(tabs.newTab().setText(R.string.history))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showingHistory = tab.position == 1
                render()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        store.bookmarks.onEach { if (!showingHistory) render() }
            .launchIn(viewLifecycleOwner.lifecycleScope)
        store.history.onEach { if (showingHistory) render() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        store.refreshBookmarks()
        store.refreshHistory()
    }

    private fun render() {
        clearButton.visibility = if (showingHistory) View.VISIBLE else View.GONE

        val rows: List<Pair<String, String>> = if (showingHistory) {
            store.history.value.map { it.title to it.url }
        } else {
            store.bookmarks.value.map { it.title to it.url }
        }

        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = getString(
            if (showingHistory) R.string.empty_history else R.string.empty_bookmarks
        )
        adapter.submit(rows)
    }
}

class BookmarkAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    private val items = mutableListOf<Pair<String, String>>()

    fun submit(list: List<Pair<String, String>>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bookmark, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.bm_title)
        private val url: TextView = itemView.findViewById(R.id.bm_url)

        fun bind(pair: Pair<String, String>) {
            title.text = pair.first
            url.text = pair.second
            itemView.setOnClickListener { onClick(pair.second) }
        }
    }
}
