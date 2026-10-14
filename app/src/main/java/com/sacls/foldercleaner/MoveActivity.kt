package com.sacls.foldercleaner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Màn di chuyển file từ thư mục nguồn (SAF) sang thẻ SD (SAF). Logic an toàn nằm ở [MoveEngine].
 * Màn này lo chọn nguồn/đích, hiển thị tiến trình, và cho hủy giữa chừng.
 */
class MoveActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var tvSource: android.widget.TextView
    private lateinit var tvDest: android.widget.TextView
    private lateinit var progressBox: View
    private lateinit var tvProgressTitle: android.widget.TextView
    private lateinit var tvProgressDetail: android.widget.TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvResult: android.widget.TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var srcTree: Uri? = null
    private var dstTree: Uri? = null

    private var moveJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    private val pickSource = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takeGrant(uri)
            srcTree = uri
            prefs.moveSrcUri = uri.toString()
            tvSource.text = label(uri)
            updateStartEnabled()
        }
    }

    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takeGrant(uri)
            dstTree = uri
            prefs.moveDestUri = uri.toString()
            tvDest.text = label(uri)
            updateStartEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_move)
        prefs = Prefs(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            if (moveJob?.isActive == true) requestCancel() else finish()
        }
        tvSource = findViewById(R.id.tvSource)
        tvDest = findViewById(R.id.tvDest)
        progressBox = findViewById(R.id.progressBox)
        tvProgressTitle = findViewById(R.id.tvProgressTitle)
        tvProgressDetail = findViewById(R.id.tvProgressDetail)
        progressBar = findViewById(R.id.progressBar)
        tvResult = findViewById(R.id.tvResult)
        btnStart = findViewById(R.id.btnStartMove)
        btnCancel = findViewById(R.id.btnCancelMove)

        // Khôi phục nguồn & đích đã chọn lần trước, nếu grant còn hiệu lực.
        srcTree = restore(prefs.moveSrcUri)?.also { tvSource.text = label(it) }
        dstTree = restore(prefs.moveDestUri)?.also { tvDest.text = label(it) }

        findViewById<MaterialButton>(R.id.btnPickSource).setOnClickListener {
            pickSource.launch(CleanerEngine.initialPickerUri("/storage/emulated/0/DCIM/Camera"))
        }
        findViewById<MaterialButton>(R.id.btnPickDest).setOnClickListener {
            // Mở picker THẲNG vào gốc thẻ SD. Nếu không ép initial URI, trình chọn của
            // Samsung One UI mở ở bộ nhớ trong và giấu thẻ SD sau menu -> người dùng tưởng
            // không chọn được thẻ. Nếu không dò được thẻ thì để null (picker mở mặc định).
            val initial = dstTree ?: sdCardInitialUri()
            pickDest.launch(initial)
        }
        btnStart.setOnClickListener { confirmAndStart() }
        btnCancel.setOnClickListener { requestCancel() }

        updateStartEnabled()
    }

    private fun updateStartEnabled() {
        btnStart.isEnabled = srcTree != null && dstTree != null && moveJob?.isActive != true
    }

    private fun confirmAndStart() {
        val src = srcTree ?: return
        val dst = dstTree ?: return
        if (src == dst) {
            snack(getString(R.string.move_same_tree))
            return
        }

        tvResult.text = getString(R.string.move_scanning)
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { MoveEngine.listSourceFiles(applicationContext, src) }
            if (files.isEmpty()) {
                tvResult.text = getString(R.string.move_nothing)
                return@launch
            }
            val bytes = files.sumOf { it.size }
            MaterialAlertDialogBuilder(this@MoveActivity)
                .setTitle(R.string.move_confirm_title)
                .setMessage(
                    getString(R.string.move_confirm_msg, files.size, CleanerEngine.formatBytes(bytes))
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.move_start) { _, _ -> startMove(src, dst, files) }
                .show()
        }
    }

    private fun startMove(src: Uri, dst: Uri, files: List<MoveEngine.FileEntry>) {
        cancelFlag.set(false)
        setRunning(true)
        progressBar.max = 1000
        tvResult.text = ""

        moveJob = lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                MoveEngine.move(
                    context = applicationContext,
                    srcTreeUri = src,
                    dstTreeUri = dst,
                    files = files,
                    onProgress = { p -> runOnUiThread { renderProgress(p) } },
                    canceller = { !cancelFlag.get() }
                )
            }
            setRunning(false)
            tvResult.text = getString(
                R.string.move_done, summary.moved, summary.skipped, summary.failed, summary.total
            ) + if (summary.skips.isNotEmpty()) {
                "\n\n" + summary.skips.take(20).joinToString("\n") { "• ${it.name}: ${it.reason}" } +
                    if (summary.skips.size > 20) "\n…" else ""
            } else ""
        }
    }

    private fun renderProgress(p: MoveEngine.Progress) {
        progressBox.visibility = View.VISIBLE
        tvProgressTitle.text = getString(R.string.move_progress_title, p.index, p.total, p.currentName)
        val pct = if (p.totalBytes > 0) (p.movedBytes * 1000 / p.totalBytes).toInt() else 0
        progressBar.setProgressCompat(pct.coerceIn(0, 1000), true)
        tvProgressDetail.text = getString(
            R.string.move_progress_detail,
            CleanerEngine.formatBytes(p.movedBytes),
            CleanerEngine.formatBytes(p.totalBytes),
            p.movedFiles, p.skippedFiles, p.failedFiles
        )
    }

    private fun requestCancel() {
        cancelFlag.set(true)
        btnCancel.isEnabled = false
    }

    private fun setRunning(running: Boolean) {
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnCancel.visibility = if (running) View.VISIBLE else View.GONE
        btnCancel.isEnabled = running
        findViewById<MaterialButton>(R.id.btnPickSource).isEnabled = !running
        findViewById<MaterialButton>(R.id.btnPickDest).isEnabled = !running
        if (!running) updateStartEnabled()
    }

    /**
     * URI trỏ vào gốc thẻ SD rời cho EXTRA_INITIAL_URI của picker.
     *
     * Dò volume removable qua StorageManager rồi lấy uuid của nó (ví dụ "5637-7A9D") để dựng
     * documentId "<uuid>:". Không hardcode uuid vì mỗi thẻ/máy một khác. Trả null nếu không có
     * thẻ -> picker mở ở vị trí mặc định.
     */
    private fun sdCardInitialUri(): Uri? {
        return try {
            val sm = getSystemService(android.content.Context.STORAGE_SERVICE)
                as android.os.storage.StorageManager
            val vol = sm.storageVolumes.firstOrNull { it.isRemovable && !it.isPrimary }
                ?: return null
            val uuid = vol.uuid ?: return null
            DocumentsContract.buildDocumentUri(
                CleanerEngine.EXTERNAL_STORAGE_AUTHORITY,
                "$uuid:"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun takeGrant(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // Không giữ được quyền lâu dài vẫn dùng được trong phiên; chỉ log ngầm.
        }
    }

    /**
     * Khôi phục URI đã lưu nếu grant ghi vẫn còn hiệu lực. Trả null nếu chưa lưu hoặc grant
     * đã mất (lúc đó người dùng cần chọn lại) — không giữ URI chết để tránh lỗi khi dùng.
     */
    private fun restore(saved: String?): Uri? {
        val uri = saved?.let { Uri.parse(it) } ?: return null
        return if (hasPersistedGrant(uri)) uri else null
    }

    private fun hasPersistedGrant(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private fun label(treeUri: Uri): String = try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = docId.split(":", limit = 2)
        val volume = if (parts[0] == "primary") getString(R.string.volume_internal) else parts[0]
        val path = parts.getOrNull(1).orEmpty()
        if (path.isEmpty()) volume else "$volume: $path"
    } catch (e: Exception) {
        treeUri.toString()
    }

    private fun snack(msg: String) {
        Snackbar.make(findViewById(R.id.toolbar), msg, Snackbar.LENGTH_LONG).show()
    }
}
