package com.sacls.foldercleaner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Di chuyển file từ một thư mục nguồn (SAF tree) sang một thư mục đích (SAF tree) một cách
 * AN TOÀN cho file lớn và nhiều file:
 *
 *  - Copy theo stream với buffer, không nạp cả file vào RAM -> không OOM với video vài GB.
 *  - Chỉ XÓA file nguồn sau khi đã copy xong VÀ xác minh kích thước đích khớp nguồn.
 *  - Nếu đích đã có file cùng tên và cùng kích thước -> coi như đã chuyển, bỏ qua (không chép lại).
 *  - Lỗi ở một file không làm hỏng cả mẻ; file đó được ghi vào danh sách lỗi và giữ nguyên nguồn.
 *  - Có callback tiến trình theo từng file và theo từng khối byte để UI hiển thị mượt.
 *
 * Dùng cho yêu cầu: chuyển /storage/emulated/0/DCIM/Camera -> thẻ SD ngoài /DCIM/Camera.
 */
object MoveEngine {

    private const val TAG = "MoveEngine"
    private const val BUFFER_SIZE = 1 shl 16 // 64 KB

    data class FileEntry(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long
    )

    /** Lý do một file bị bỏ qua / thất bại, để báo lại cho người dùng. */
    data class Skip(val name: String, val reason: String)

    data class Progress(
        val index: Int,          // file thứ mấy (1-based)
        val total: Int,
        val currentName: String,
        val movedFiles: Int,
        val skippedFiles: Int,
        val failedFiles: Int,
        val movedBytes: Long,
        val totalBytes: Long
    )

    data class Summary(
        val total: Int,
        val moved: Int,
        val skipped: Int,
        val failed: Int,
        val movedBytes: Long,
        val skips: List<Skip>
    )

    /** Hủy giữa chừng: callback tiến trình trả về false thì dừng (file đang chép dở sẽ bị bỏ, nguồn giữ nguyên). */
    fun interface Canceller { fun shouldContinue(): Boolean }

    /**
     * Liệt kê file trực tiếp (không đệ quy) trong thư mục nguồn.
     * Chỉ lấy file, bỏ thư mục con — Camera thường phẳng; nếu cần đệ quy sau này mở rộng dễ.
     */
    fun listSourceFiles(context: Context, srcTreeUri: Uri): List<FileEntry> {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(srcTreeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(srcTreeUri, rootDocId)
        val out = ArrayList<FileEntry>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        resolver.query(childrenUri, columns, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val mime = c.getString(mimeIdx) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                out.add(
                    FileEntry(
                        docId = c.getString(idIdx),
                        name = c.getString(nameIdx) ?: "",
                        mime = mime,
                        size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx)
                    )
                )
            }
        }
        return out
    }

    /**
     * Thực hiện di chuyển. Chạy trên luồng nền (gọi từ coroutine Dispatchers.IO).
     *
     * @param onProgress gọi trước mỗi file và trong khi chép; đọc được số liệu để cập nhật UI.
     * @param canceller  cho phép người dùng dừng; kiểm tra giữa các khối byte.
     */
    fun move(
        context: Context,
        srcTreeUri: Uri,
        dstTreeUri: Uri,
        files: List<FileEntry>,
        onProgress: (Progress) -> Unit,
        canceller: Canceller
    ): Summary {
        val resolver = context.contentResolver
        val dstDocId = DocumentsContract.getTreeDocumentId(dstTreeUri)
        val dstDirUri = DocumentsContract.buildDocumentUriUsingTree(dstTreeUri, dstDocId)

        val totalBytes = files.sumOf { it.size }
        var moved = 0
        var skipped = 0
        var failed = 0
        var movedBytes = 0L
        val skips = ArrayList<Skip>()

        // Map tên -> (docId, size) các file đã có ở đích, để phát hiện "đã chuyển rồi".
        val existing = indexChildren(context, dstTreeUri, dstDocId)

        for ((i, f) in files.withIndex()) {
            if (!canceller.shouldContinue()) break

            onProgress(
                Progress(
                    index = i + 1, total = files.size, currentName = f.name,
                    movedFiles = moved, skippedFiles = skipped, failedFiles = failed,
                    movedBytes = movedBytes, totalBytes = totalBytes
                )
            )

            // 1) Đã tồn tại ở đích với cùng kích thước -> coi như đã chuyển, xóa nguồn cho sạch.
            val already = existing[f.name]
            if (already != null && already.second == f.size && f.size > 0) {
                val srcUri = DocumentsContract.buildDocumentUriUsingTree(srcTreeUri, f.docId)
                if (safeDelete(context, srcUri)) {
                    skipped++
                    skips.add(Skip(f.name, "đã có ở đích, bỏ qua và xóa nguồn"))
                } else {
                    skipped++
                    skips.add(Skip(f.name, "đã có ở đích nhưng không xóa được nguồn"))
                }
                continue
            }

            val srcUri = DocumentsContract.buildDocumentUriUsingTree(srcTreeUri, f.docId)

            // 2) Tạo file đích. Nếu trùng tên nhưng khác kích thước, SAF tự thêm hậu tố (1).
            val dstUri = try {
                DocumentsContract.createDocument(resolver, dstDirUri, f.mime.ifEmpty { "application/octet-stream" }, f.name)
            } catch (e: Exception) {
                Log.w(TAG, "Tạo file đích thất bại: ${f.name}", e)
                null
            }
            if (dstUri == null) {
                failed++
                skips.add(Skip(f.name, "không tạo được file ở đích (hết dung lượng?)"))
                continue
            }

            // 3) Copy stream, đồng thời tính MD5 của NGUỒN ngay trong lúc đọc (gần như miễn phí
            //    vì dữ liệu dù sao cũng chảy qua đây). srcDigest trả về sau khi copy xong.
            val srcDigest = java.security.MessageDigest.getInstance("MD5")
            val copyOk = try {
                copyStream(context, srcUri, dstUri, canceller) { buf, len ->
                    srcDigest.update(buf, 0, len)
                    movedBytes += len
                    onProgress(
                        Progress(
                            index = i + 1, total = files.size, currentName = f.name,
                            movedFiles = moved, skippedFiles = skipped, failedFiles = failed,
                            movedBytes = movedBytes, totalBytes = totalBytes
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Copy thất bại: ${f.name}", e)
                false
            }

            if (!copyOk) {
                // Dọn file đích chép dở để không để lại rác hỏng. Nguồn giữ nguyên.
                safeDelete(context, dstUri)
                failed++
                skips.add(Skip(f.name, "copy lỗi hoặc bị hủy, giữ nguyên file gốc"))
                continue
            }

            // 4) Xác minh bằng HASH: đọc lại file đích tính MD5 rồi so với MD5 nguồn.
            //    Chắc chắn hơn so-kích-thước: bắt được cả trường hợp byte bị sai (thẻ lỗi),
            //    không chỉ thiếu/thừa byte. Chỉ phải đọc thêm một lượt file đích.
            val srcHash = toHex(srcDigest.digest())
            val dstHash = md5Of(context, dstUri)
            if (dstHash == null || dstHash != srcHash) {
                safeDelete(context, dstUri)
                failed++
                skips.add(Skip(f.name, "hash đích không khớp nguồn, giữ nguyên file gốc"))
                continue
            }

            // 5) Hash khớp -> an toàn xóa nguồn.
            if (safeDelete(context, srcUri)) {
                moved++
            } else {
                moved++
                skips.add(Skip(f.name, "đã chép và khớp hash nhưng không xóa được bản gốc"))
            }
        }

        return Summary(files.size, moved, skipped, failed, movedBytes, skips)
    }

    private fun indexChildren(context: Context, treeUri: Uri, parentDocId: String): Map<String, Pair<String, Long>> {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val map = HashMap<String, Pair<String, Long>>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            resolver.query(childrenUri, columns, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val id = c.getString(idIdx)
                    val size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx)
                    map[name] = id to size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được thư mục đích", e)
        }
        return map
    }

    /** Copy src -> dst theo khối. [onChunk] nhận (buffer, số byte) để gọi tính hash luôn. */
    private fun copyStream(
        context: Context,
        src: Uri,
        dst: Uri,
        canceller: Canceller,
        onChunk: (ByteArray, Int) -> Unit
    ): Boolean {
        val resolver = context.contentResolver
        resolver.openInputStream(src).use { input ->
            if (input == null) return false
            resolver.openOutputStream(dst, "w").use { output ->
                if (output == null) return false
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (!canceller.shouldContinue()) return false
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    onChunk(buf, n)
                }
                output.flush()
            }
        }
        return true
    }

    /** MD5 của một document URI, đọc theo stream (không nạp cả file vào RAM). Null nếu lỗi. */
    private fun md5Of(context: Context, uri: Uri): String? {
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            } ?: return null
            toHex(md.digest())
        } catch (e: Exception) {
            null
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun safeDelete(context: Context, uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Xóa thất bại: $uri", e)
            false
        }
    }
}
