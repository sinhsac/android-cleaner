package com.sacls.foldercleaner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Lõi quét và xóa file trong một thư mục đã được cấp quyền qua SAF.
 *
 * Dùng [DocumentsContract] + ContentResolver.query trực tiếp (nhanh hơn nhiều so với
 * DocumentFile.listFiles() khi thư mục chứa hàng trăm/nghìn file), và duyệt thư mục con
 * bằng vòng lặp (BFS) để tránh tràn stack.
 */
object CleanerEngine {

    private const val TAG = "CleanerEngine"

    /** Kết quả của một lần quét/dọn. */
    data class Result(
        val matchedCount: Int,
        val deletedCount: Int,
        val totalBytes: Long,
        val errors: List<String>,
        val dryRun: Boolean
    )

    /**
     * Quét (và tùy chọn xóa) file khớp đuôi trong [treeUri].
     *
     * @param treeUri      URI thư mục gốc do SAF cấp (ACTION_OPEN_DOCUMENT_TREE).
     * @param extensions   danh sách đuôi (không dấu chấm, chữ thường), ví dụ ["mp4","jpg"].
     * @param recursive    có duyệt thư mục con không.
     * @param dryRun        true = chỉ đếm, không xóa (Quét thử).
     * @param progress     callback báo tiến độ: (số file đã xử lý, tên file hiện tại).
     */
    fun run(
        context: Context,
        treeUri: Uri,
        extensions: List<String>,
        recursive: Boolean,
        dryRun: Boolean,
        progress: ((processed: Int, currentName: String) -> Unit)? = null
    ): Result {
        if (extensions.isEmpty()) {
            return Result(0, 0, 0L, listOf("Chưa cấu hình đuôi file nào."), dryRun)
        }

        val resolver = context.contentResolver
        val extSet = extensions.map { it.lowercase() }.toSet()

        var matched = 0
        var deleted = 0
        var totalBytes = 0L
        var processed = 0
        val errors = ArrayList<String>()

        // documentId của thư mục gốc trong cây treeUri.
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

        // Hàng đợi các thư mục cần duyệt (lưu documentId).
        val dirQueue = ArrayDeque<String>()
        dirQueue.add(rootDocId)

        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        while (dirQueue.isNotEmpty()) {
            val parentDocId = dirQueue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

            val cursor = try {
                resolver.query(childrenUri, columns, null, null, null)
            } catch (e: Exception) {
                errors.add("Không đọc được thư mục: ${e.message}")
                null
            } ?: continue

            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (c.moveToNext()) {
                    val docId = c.getString(idIdx)
                    val name = c.getString(nameIdx) ?: ""
                    val mime = c.getString(mimeIdx)
                    val size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx)

                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    if (isDir) {
                        if (recursive) dirQueue.add(docId)
                        continue
                    }

                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext.isNotEmpty() && extSet.contains(ext)) {
                        matched++
                        totalBytes += size
                        processed++
                        progress?.invoke(processed, name)

                        if (!dryRun) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            val ok = try {
                                DocumentsContract.deleteDocument(resolver, fileUri)
                            } catch (e: Exception) {
                                Log.w(TAG, "Xóa thất bại: $name", e)
                                false
                            }
                            if (ok) deleted++ else errors.add("Xóa thất bại: $name")
                        }
                    }
                }
            }
        }

        return Result(matched, deleted, totalBytes, errors, dryRun)
    }

    /**
     * Quét (và tùy chọn xóa) file khớp đuôi trong một thư mục cho bởi ĐƯỜNG DẪN TUYỆT ĐỐI,
     * dùng java.io.File (legacy storage) — không qua SAF. Dành cho Android/data trên Android 11
     * khi app target API thấp.
     */
    fun runPath(
        absolutePath: String,
        extensions: List<String>,
        recursive: Boolean,
        dryRun: Boolean
    ): Result {
        if (extensions.isEmpty()) {
            return Result(0, 0, 0L, listOf("Chưa cấu hình đuôi file nào."), dryRun)
        }
        val root = java.io.File(absolutePath)
        if (!root.exists()) {
            return Result(0, 0, 0L, listOf("Thư mục không tồn tại hoặc không đọc được: $absolutePath"), dryRun)
        }
        if (!root.isDirectory) {
            return Result(0, 0, 0L, listOf("Đường dẫn không phải thư mục: $absolutePath"), dryRun)
        }

        val extSet = extensions.map { it.lowercase() }.toSet()
        var matched = 0
        var deleted = 0
        var totalBytes = 0L
        val errors = ArrayList<String>()

        val dirQueue = ArrayDeque<java.io.File>()
        dirQueue.add(root)

        while (dirQueue.isNotEmpty()) {
            val dir = dirQueue.removeFirst()
            val children = dir.listFiles()
            if (children == null) {
                errors.add("Không liệt kê được: ${dir.absolutePath}")
                continue
            }
            for (f in children) {
                if (f.isDirectory) {
                    if (recursive) dirQueue.add(f)
                    continue
                }
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty() && extSet.contains(ext)) {
                    matched++
                    totalBytes += f.length()
                    if (!dryRun) {
                        val ok = try {
                            f.delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Xóa thất bại: ${f.name}", e)
                            false
                        }
                        if (ok) deleted++ else errors.add("Xóa thất bại: ${f.name}")
                    }
                }
            }
        }
        return Result(matched, deleted, totalBytes, errors, dryRun)
    }

    /** Định dạng dung lượng cho dễ đọc. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }
}
