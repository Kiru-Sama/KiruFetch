package com.kirufetch.app.ui.screens

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.kirufetch.app.R
import com.kirufetch.app.data.DownloadEntity
import com.kirufetch.app.data.DownloadStore
import com.kirufetch.app.download.DownloadManager
import com.kirufetch.app.download.StorageTarget
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DownloadsFragment : Fragment(R.layout.fragment_downloads) {

    private lateinit var adapter: DownloadAdapter
    private lateinit var emptyView: View
    private var showingDone = false
    private var latest: List<DownloadEntity> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val manager = DownloadManager.get(requireContext())
        val store = DownloadStore.get(requireContext())

        adapter = DownloadAdapter(
            onPause = { manager.pause(it.id) },
            // resume 内部会带上库里存的 variantUrl 重新排队，这里不用再手动 start
            onResume = { entity -> manager.resume(entity.id) },
            onDelete = { entity -> confirmDelete(entity, manager) },
            onOpen = { entity ->
                val ctx = requireContext()
                if (!StorageTarget.exists(ctx, entity.outputPath)) {
                    android.widget.Toast.makeText(
                        ctx, R.string.file_missing, android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    startActivity(
                        android.content.Intent(ctx, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_PATH, entity.outputPath)
                    )
                }
            }
        )

        val recyclerView = view.findViewById<RecyclerView>(R.id.download_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        emptyView = view.findViewById(R.id.download_empty)

        val tabs = view.findViewById<TabLayout>(R.id.download_tabs)
        tabs.addTab(tabs.newTab().setText(R.string.tab_in_progress))
        tabs.addTab(tabs.newTab().setText(R.string.tab_completed))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showingDone = tab.position == 1
                render()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        store.refresh()
        store.state
            .onEach { list ->
                latest = list
                render()
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun render() {
        val rows = latest.filter { entity ->
            if (showingDone) entity.isDone else !entity.isDone
        }
        adapter.submit(rows)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        (emptyView as? android.widget.TextView)?.setText(
            if (showingDone) R.string.empty_downloads_done else R.string.empty_downloads_active
        )
    }

    private fun confirmDelete(entity: DownloadEntity, manager: DownloadManager) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_task)
            .setMessage(R.string.delete_task_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                StorageTarget.delete(requireContext(), entity.outputPath)
                manager.cancel(entity.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
