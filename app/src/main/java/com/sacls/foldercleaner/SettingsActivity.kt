package com.sacls.foldercleaner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

/**
 * Màn cấu hình: chọn thư mục cần dọn qua SAF (hành động chính), đuôi file, quét thư mục con.
 * Đường dẫn chỉ hiển thị để biết đang trỏ vào đâu, không nhập tay.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvFolder: android.widget.TextView
    private lateinit var etExtensions: TextInputEditText
    private lateinit var swRecursive: MaterialSwitch

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onFolderGranted(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        tvFolder = findViewById(R.id.tvFolder)
        etExtensions = findViewById(R.id.etExtensions)
        swRecursive = findViewById(R.id.swRecursive)

        etExtensions.setText(prefs.extensionsRaw)
        swRecursive.isChecked = prefs.recursive
        refreshFolderLabel()

        findViewById<MaterialButton>(R.id.btnBrowse).setOnClickListener {
            // Mở picker ngay tại thư mục đang cấu hình để chỉ cần "Sử dụng thư mục này".
            pickFolder.launch(CleanerEngine.initialPickerUri(prefs.absolutePath))
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.extensionsRaw = (etExtensions.text?.toString()?.trim().orEmpty())
            .ifEmpty { Prefs.DEFAULT_EXTENSIONS }
        prefs.recursive = swRecursive.isChecked
    }

    private fun onFolderGranted(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            prefs.treeUri = uri.toString()

            val granted = runCatching { DocumentsContract.getTreeDocumentId(uri) }
                .getOrNull()
                ?.let { CleanerEngine.pathForDocId(it) }
            if (granted != null) prefs.absolutePath = granted

            refreshFolderLabel()
            snack(getString(R.string.folder_selected))
        } catch (e: Exception) {
            snack(getString(R.string.folder_grant_failed, e.message ?: ""))
        }
    }

    private fun refreshFolderLabel() {
        val grant = CleanerEngine.findGrant(applicationContext, prefs.absolutePath)
        tvFolder.text = if (grant == null) {
            getString(R.string.no_folder)
        } else {
            friendlyPath(grant.first)
        }
    }

    private fun friendlyPath(treeUri: Uri): String = try {
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
