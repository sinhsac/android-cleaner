package com.sacls.foldercleaner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sacls.foldercleaner.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Chọn thư mục qua SAF; nhận về tree URI và giữ quyền vĩnh viễn. */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                prefs.treeUri = uri.toString()
                refreshFolderLabel()
                snack(getString(R.string.folder_selected))
            } catch (e: Exception) {
                snack(getString(R.string.folder_grant_failed, e.message ?: ""))
            }
        }
    }

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* kết quả không bắt buộc xử lý */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // Nạp cấu hình đã lưu lên UI.
        binding.etExtensions.setText(prefs.extensionsRaw)
        binding.swRecursive.isChecked = prefs.recursive
        binding.swAuto.isChecked = prefs.autoEnabled
        binding.etInterval.setText(prefs.autoIntervalHours.toString())
        prefs.lastResult?.let { binding.tvResult.text = it }
        refreshFolderLabel()

        binding.btnBrowse.setOnClickListener {
            // Gợi ý mở sẵn ở vùng Documents; người dùng tự điều hướng tới thư mục Kabe View.
            pickFolder.launch(null)
        }

        binding.btnDryRun.setOnClickListener { persistConfig(); startRun(dryRun = true) }
        binding.btnClean.setOnClickListener { persistConfig(); confirmAndClean() }

        binding.swAuto.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoEnabled = isChecked
            if (isChecked) {
                if (prefs.treeUri == null) {
                    binding.swAuto.isChecked = false
                    prefs.autoEnabled = false
                    snack(getString(R.string.need_folder_first))
                    return@setOnCheckedChangeListener
                }
                ensureNotifPermission()
                applyAutoSchedule()
                snack(getString(R.string.auto_on))
            } else {
                CleanupScheduler.cancel(this)
                snack(getString(R.string.auto_off))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        persistConfig()
    }

    /** Lưu các giá trị trên UI vào prefs. */
    private fun persistConfig() {
        prefs.extensionsRaw = binding.etExtensions.text?.toString()?.trim().orEmpty()
            .ifEmpty { Prefs.DEFAULT_EXTENSIONS }
        prefs.recursive = binding.swRecursive.isChecked
        val hrs = binding.etInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: Prefs.DEFAULT_INTERVAL_HOURS
        prefs.autoIntervalHours = hrs.coerceAtLeast(1)
        if (prefs.autoEnabled && prefs.treeUri != null) applyAutoSchedule()
    }

    private fun applyAutoSchedule() {
        CleanupScheduler.schedule(this, prefs.autoIntervalHours)
    }

    private fun refreshFolderLabel() {
        val uriStr = prefs.treeUri
        binding.tvFolder.text = if (uriStr == null) {
            getString(R.string.no_folder)
        } else {
            friendlyPath(Uri.parse(uriStr))
        }
    }

    /** Chuyển tree URI thành đường dẫn dễ đọc, ví dụ "Internal: Android/data/com.kabe". */
    private fun friendlyPath(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // docId thường dạng "primary:Android/data/com.kabe.view"
            val parts = docId.split(":", limit = 2)
            val volume = when (parts[0]) {
                "primary" -> getString(R.string.volume_internal)
                else -> parts[0]
            }
            val path = parts.getOrNull(1).orEmpty()
            if (path.isEmpty()) volume else "$volume: $path"
        } catch (e: Exception) {
            treeUri.toString()
        }
    }

    private fun confirmAndClean() {
        val uriStr = prefs.treeUri
        if (uriStr == null) {
            snack(getString(R.string.need_folder_first))
            return
        }
        val exts = prefs.parsedExtensions()
        if (exts.isEmpty()) {
            snack(getString(R.string.need_extensions))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(getString(R.string.confirm_message, exts.joinToString(", ") { "*.$it" }))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> startRun(dryRun = false) }
            .show()
    }

    private fun startRun(dryRun: Boolean) {
        val uriStr = prefs.treeUri
        if (uriStr == null) {
            snack(getString(R.string.need_folder_first))
            return
        }
        val treeUri = Uri.parse(uriStr)
        val exts = prefs.parsedExtensions()
        if (exts.isEmpty()) {
            snack(getString(R.string.need_extensions))
            return
        }
        val recursive = binding.swRecursive.isChecked

        setBusy(true)
        binding.tvResult.text = getString(R.string.running)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CleanerEngine.run(
                    context = applicationContext,
                    treeUri = treeUri,
                    extensions = exts,
                    recursive = recursive,
                    dryRun = dryRun
                )
            }
            setBusy(false)
            val text = formatResult(result)
            binding.tvResult.text = text
            prefs.lastResult = text
        }
    }

    private fun formatResult(r: CleanerEngine.Result): String {
        val sb = StringBuilder()
        if (r.dryRun) {
            sb.appendLine(getString(R.string.result_dryrun_header))
            sb.appendLine(getString(R.string.result_matched, r.matchedCount))
            sb.appendLine(getString(R.string.result_size, CleanerEngine.formatBytes(r.totalBytes)))
        } else {
            sb.appendLine(getString(R.string.result_clean_header))
            sb.appendLine(getString(R.string.result_deleted, r.deletedCount, r.matchedCount))
            sb.appendLine(getString(R.string.result_freed, CleanerEngine.formatBytes(r.totalBytes)))
        }
        if (r.errors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(getString(R.string.result_errors, r.errors.size))
            r.errors.take(10).forEach { sb.appendLine("  - $it") }
            if (r.errors.size > 10) sb.appendLine("  ...")
        }
        return sb.toString().trim()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnDryRun.isEnabled = !busy
        binding.btnClean.isEnabled = !busy
        binding.btnBrowse.isEnabled = !busy
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun snack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }
}
