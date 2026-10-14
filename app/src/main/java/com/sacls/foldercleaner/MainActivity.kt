package com.sacls.foldercleaner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình chính: lưới thumbnail các file quét được từ thư mục đã cấu hình.
 * Mở app -> tự quét. Có nút "Dọn ngay" và menu mở Cấu hình / Chuyển DCIM.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var thumbLoader: ThumbLoader
    private lateinit var adapter: MediaAdapter

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var tvEmpty: android.widget.TextView
    private lateinit var btnEmptyAction: MaterialButton
    private lateinit var progressScan: View
    private lateinit var tvSummary: android.widget.TextView
    private lateinit var btnCleanNow: MaterialButton

    /** Danh sách file hiện có trên lưới, giữ để biết cái gì sẽ bị xóa. */
    private var current: List<CleanerEngine.MediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        thumbLoader = ThumbLoader(this, lifecycleScope)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { onMenu(it.itemId) }
        recycler = findViewById(R.id.recycler)
        emptyState = findViewById(R.id.emptyState)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnEmptyAction = findViewById(R.id.btnEmptyAction)
        progressScan = findViewById(R.id.progressScan)
        tvSummary = findViewById(R.id.tvSummary)
        btnCleanNow = findViewById(R.id.btnCleanNow)

        adapter = MediaAdapter(thumbLoader) { openItem(it) }
        recycler.layoutManager = GridLayoutManager(this, spanCount())
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        btnEmptyAction.setOnClickListener { openSettings() }
        btnCleanNow.setOnClickListener { confirmClean() }
    }

    override fun onResume() {
        super.onResume()
        // Mỗi lần quay lại (kể cả sau khi đổi cấu hình) thì quét lại.
        scan()
    }

    private fun spanCount(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 120).toInt().coerceIn(2, 5)
    }

    private fun onMenu(id: Int): Boolean = when (id) {
        R.id.action_refresh -> { scan(); true }
        R.id.action_settings -> { openSettings(); true }
        R.id.action_move_sd -> { startActivity(Intent(this, MoveActivity::class.java)); true }
        else -> false
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun scan() {
        val grant = CleanerEngine.findGrant(applicationContext, prefs.absolutePath)
        if (grant == null) {
            showEmpty(getString(R.string.empty_need_grant), getString(R.string.open_settings))
            return
        }

        setScanning(true)
        adapter.clear()
        current = emptyList()

        lifecycleScope.launch {
            val exts = prefs.parsedExtensions()
            val recursive = prefs.recursive
            val path = prefs.absolutePath
            val result = withContext(Dispatchers.IO) {
                CleanerEngine.listConfiguredFiles(applicationContext, path, exts, recursive)
                    ?: emptyList()
            }
            current = result
            setScanning(false)

            if (result.isEmpty()) {
                showEmpty(getString(R.string.empty_no_files), null)
            } else {
                emptyState.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                adapter.submit(result)
                val bytes = result.sumOf { it.size }
                tvSummary.text = getString(
                    R.string.summary_count, result.size, CleanerEngine.formatBytes(bytes)
                )
                btnCleanNow.isEnabled = true
            }
        }
    }

    private fun setScanning(scanning: Boolean) {
        progressScan.visibility = if (scanning) View.VISIBLE else View.GONE
        if (scanning) {
            emptyState.visibility = View.GONE
            btnCleanNow.isEnabled = false
            tvSummary.text = getString(R.string.scanning)
        }
    }

    private fun showEmpty(message: String, actionLabel: String?) {
        recycler.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        tvEmpty.text = message
        btnEmptyAction.visibility = if (actionLabel != null) View.VISIBLE else View.GONE
        actionLabel?.let { btnEmptyAction.text = it }
        tvSummary.text = getString(R.string.summary_placeholder)
        btnCleanNow.isEnabled = false
    }

    private fun confirmClean() {
        if (current.isEmpty()) return
        val bytes = current.sumOf { it.size }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(
                getString(R.string.confirm_message, current.size, CleanerEngine.formatBytes(bytes))
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> doClean() }
            .show()
    }

    private fun doClean() {
        val toDelete = current
        setScanning(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CleanerEngine.deleteUris(applicationContext, toDelete)
            }
            setScanning(false)
            snack(
                getString(
                    R.string.deleted_summary,
                    result.deletedCount, result.matchedCount,
                    CleanerEngine.formatBytes(result.totalBytes)
                )
            )
            scan()
        }
    }

    private fun openItem(item: CleanerEngine.MediaItem) {
        val mime = if (item.isVideo) "video/*" else "image/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            snack(getString(R.string.open_failed, item.name))
        }
    }

    private fun snack(msg: String) {
        Snackbar.make(findViewById(R.id.recycler), msg, Snackbar.LENGTH_LONG).show()
    }
}
