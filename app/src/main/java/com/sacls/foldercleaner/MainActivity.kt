package com.sacls.foldercleaner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    /** Chọn thư mục qua SAF (phương án phụ). */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                prefs.treeUri = uri.toString()
                prefs.usePathMode = false   // vừa chọn SAF -> chuyển sang chế độ SAF
                refreshFolderLabel()
                snack(getString(R.string.folder_selected))
            } catch (e: Exception) {
                snack(getString(R.string.folder_grant_failed, e.message ?: ""))
            }
        }
    }

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        snack(if (granted) getString(R.string.storage_granted) else getString(R.string.storage_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.etPath.setText(prefs.absolutePath)
        binding.etExtensions.setText(prefs.extensionsRaw)
        binding.swRecursive.isChecked = prefs.recursive
        binding.swAuto.isChecked = prefs.autoEnabled
        binding.etInterval.setText(prefs.autoIntervalHours.toString())
        prefs.lastResult?.let { binding.tvResult.text = it }
        refreshFolderLabel()

        binding.btnGrant.setOnClickListener { requestStoragePermission() }

        binding.btnBrowse.setOnClickListener { pickFolder.launch(null) }

        binding.btnDryRun.setOnClickListener { persistConfig(); startRun(dryRun = true) }
        binding.btnClean.setOnClickListener { persistConfig(); confirmAndClean() }

        binding.swAuto.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoEnabled = isChecked
            if (isChecked) {
                persistConfig()
                if (!hasTarget()) {
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

    /** Có mục tiêu hợp lệ để dọn chưa? (path mode cần path; SAF mode cần treeUri) */
    private fun hasTarget(): Boolean =
        if (prefs.usePathMode) prefs.absolutePath.isNotBlank() else prefs.treeUri != null

    private fun persistConfig() {
        val path = binding.etPath.text?.toString()?.trim().orEmpty()
        if (path.isNotEmpty()) {
            prefs.absolutePath = path
            prefs.usePathMode = true   // có nhập path -> ưu tiên chế độ path
        }
        prefs.extensionsRaw = (binding.etExtensions.text?.toString()?.trim().orEmpty())
            .ifEmpty { Prefs.DEFAULT_EXTENSIONS }
        prefs.recursive = binding.swRecursive.isChecked
        val hrs = binding.etInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: Prefs.DEFAULT_INTERVAL_HOURS
        prefs.autoIntervalHours = hrs.coerceAtLeast(1)
        if (prefs.autoEnabled && hasTarget()) applyAutoSchedule()
    }

    private fun applyAutoSchedule() {
        CleanupScheduler.schedule(this, prefs.autoIntervalHours)
    }

    private fun refreshFolderLabel() {
        val uriStr = prefs.treeUri
        binding.tvFolder.text = if (uriStr == null) {
            getString(R.string.no_folder)
        } else {
            getString(R.string.saf_selected, friendlyPath(Uri.parse(uriStr)))
        }
    }

    private fun friendlyPath(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
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
        if (!hasTarget()) {
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
        if (!hasTarget()) {
            snack(getString(R.string.need_folder_first))
            return
        }
        val exts = prefs.parsedExtensions()
        if (exts.isEmpty()) {
            snack(getString(R.string.need_extensions))
            return
        }
        val recursive = binding.swRecursive.isChecked
        val usePath = prefs.usePathMode
        val path = prefs.absolutePath
        val treeUriStr = prefs.treeUri

        setBusy(true)
        binding.tvResult.text = getString(R.string.running)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (usePath) {
                    CleanerEngine.runPath(
                        absolutePath = path,
                        extensions = exts,
                        recursive = recursive,
                        dryRun = dryRun
                    )
                } else {
                    CleanerEngine.run(
                        context = applicationContext,
                        treeUri = Uri.parse(treeUriStr),
                        extensions = exts,
                        recursive = recursive,
                        dryRun = dryRun
                    )
                }
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
        binding.btnGrant.isEnabled = !busy
    }

    private fun requestStoragePermission() {
        val perms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            snack(getString(R.string.storage_granted))
        } else {
            requestStorage.launch(perms)
        }
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
