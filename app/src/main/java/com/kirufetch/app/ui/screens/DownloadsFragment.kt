package com.kirufetch.app.ui.screens

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
    private lateinit var batchBar: View
    private lateinit var batchCount: TextView
    private lateinit var batchAll: TextView
    private lateinit var manager: DownloadManager
    private var showingDone = false
    private var latest: List<DownloadEntity> = emptyList()
    private var currentRows: List<DownloadEntity> = emptyList()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitSelection()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        manager = DownloadManager.get(requireContext())
        val store = DownloadStore.get(requireContext())

        adapter = DownloadAdapter(
            onPause = { manager.pause(it.id) },
            // resume 内部会带上库里存的 variantUrl 重新排队，这里不用再手动 start
            onResume = { entity -> manager.resume(entity.id) },
            onOpen = { entity -> openPlayer(entity) },
            onLongPress = { entity ->
                if (!adapter.selectionMode) {
                    adapter.enterSelection(entity.id)
                } else {
                    adapter.toggle(entity.id)
                }
                refreshBatchBar()
            },
            onToggleSelect = { entity ->
                adapter.toggle(entity.id)
                if (adapter.selectedCount() == 0) exitSelection() else refreshBatchBar()
            }
        )

        val recyclerView = view.findViewById<RecyclerView>(R.id.download_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        emptyView = view.findViewById(R.id.download_empty)

        batchBar = view.findViewById(R.id.batch_bar)
        batchCount = view.findViewById(R.id.batch_count)
        batchAll = view.findViewById(R.id.batch_all)
        view.findViewById<View>(R.id.batch_cancel).setOnClickListener { exitSelection() }
        view.findViewById<View>(R.id.batch_delete).setOnClickListener { confirmBatchDelete() }
        view.findViewById<View>(R.id.batch_pause).setOnClickListener { pauseSelected() }
        batchAll.setOnClickListener {
            val ids = currentRows.map { it.id }
            if (adapter.allSelected(ids)) exitSelection() else {
                adapter.selectAll(ids)
                refreshBatchBar()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val tabs = view.findViewById<TabLayout>(R.id.download_tabs)
        tabs.addTab(tabs.newTab().setText(R.string.tab_in_progress))
        tabs.addTab(tabs.newTab().setText(R.string.tab_completed))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showingDone = tab.position == 1
                exitSelection()
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

    private fun openPlayer(entity: DownloadEntity) {
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

    private fun render() {
        val rows = latest.filter { entity ->
            if (showingDone) entity.isDone else !entity.isDone
        }
        currentRows = rows
        adapter.submit(rows)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        (emptyView as? TextView)?.setText(
            if (showingDone) R.string.empty_downloads_done else R.string.empty_downloads_active
        )
        if (rows.isEmpty() && adapter.selectionMode) exitSelection()
        refreshBatchBar()
    }

    private fun refreshBatchBar() {
        val on = adapter.selectionMode
        batchBar.visibility = if (on) View.VISIBLE else View.GONE
        backCallback.isEnabled = on
        if (!on) return
        batchCount.text = getString(R.string.batch_selected, adapter.selectedCount())
        batchAll.text = getString(
            if (adapter.allSelected(currentRows.map { it.id })) R.string.batch_none
            else R.string.batch_all
        )
    }

    private fun exitSelection() {
        adapter.exitSelection()
        refreshBatchBar()
    }

    private fun pauseSelected() {
        val ids = adapter.selectedIds().toSet()
        currentRows.filter { it.id in ids && it.isRunning }.forEach { manager.pause(it.id) }
        refreshBatchBar()
    }

    private fun confirmBatchDelete() {
        val ids = adapter.selectedIds().toSet()
        if (ids.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.batch_delete_title, ids.size))
            .setMessage(R.string.batch_delete_message)
            .setPositiveButton(R.string.delete_record_only) { _, _ -> deleteSelected(ids, false) }
            .setNeutralButton(R.string.delete_with_file) { _, _ -> deleteSelected(ids, true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSelected(ids: Set<String>, withFiles: Boolean) {
        currentRows.filter { it.id in ids }.forEach { entity ->
            if (withFiles) StorageTarget.delete(requireContext(), entity.outputPath)
            manager.cancel(entity.id)
        }
        exitSelection()
    }
}
