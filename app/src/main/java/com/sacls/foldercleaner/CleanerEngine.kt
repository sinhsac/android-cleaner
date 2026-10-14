package com.sacls.foldercleaner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Lõi quét và xóa file theo đuôi trong một thư mục.
 *
 * Có hai cách truy cập, [clean] tự chọn:
 *  - SAF ([run]): bắt buộc với `Android/data` của app khác. Dùng [DocumentsContract] +
 *    ContentResolver.query trực tiếp, nhanh hơn nhiều DocumentFile.listFiles() khi thư mục
 *    có hàng trăm/nghìn file.
 *  - java.io.File ([runPath]): cho các thư mục thường như DCIM, Download.
 *
 * Cả hai đều duyệt thư mục con bằng vòng lặp (BFS) để tránh tràn stack.
 */
object CleanerEngine {

    private const val TAG = "CleanerEngine"

    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** Authority của DocumentsProvider cho bộ nhớ trong/thẻ nhớ. */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Kết quả của một lần quét/dọn. */
    data class Result(
        val matchedCount: Int,
        val deletedCount: Int,
        val totalBytes: Long,
        val errors: List<String>,
        val dryRun: Boolean
    )

    /** Một file khớp đuôi, đủ thông tin để hiển thị trên lưới và để xóa. */
    data class MediaItem(
        /** URI document (buildDocumentUriUsingTree) — dùng để load thumbnail, mở, hoặc xóa. */
        val uri: Uri,
        val name: String,
        val size: Long,
        val isVideo: Boolean
    )

    /**
     * Liệt kê (KHÔNG xóa) các file khớp đuôi trong một thư mục đã có SAF grant.
     *
     * Trả về danh sách để màn hình chính hiển thị lưới thumbnail. Có [onItem] để stream
     * dần từng file ra UI thay vì đợi quét xong toàn bộ — thư mục nhiều file vẫn mượt.
     */
    fun listFiles(
        context: Context,
        treeUri: Uri,
        extensions: List<String>,
        recursive: Boolean,
        startDocId: String? = null,
        onItem: ((MediaItem) -> Unit)? = null
    ): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        if (extensions.isEmpty()) return out

        val resolver = context.contentResolver
        val extSet = extensions.map { it.lowercase() }.toSet()
        val rootDocId = startDocId ?: DocumentsContract.getTreeDocumentId(treeUri)

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

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (recursive) dirQueue.add(docId)
                        continue
                    }

                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext.isNotEmpty() && extSet.contains(ext)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val isVideo = (mime?.startsWith("video/") == true) || ext in VIDEO_EXTS
                        val item = MediaItem(uri, name, size, isVideo)
                        out.add(item)
                        onItem?.invoke(item)
                    }
                }
            }
        }
        return out
    }

    /** Xóa một danh sách document URI. Trả về (số xóa được, tổng bytes xóa, lỗi). */
    fun deleteUris(context: Context, items: List<MediaItem>): Result {
        val resolver = context.contentResolver
        var deleted = 0
        var bytes = 0L
        val errors = ArrayList<String>()
        for (item in items) {
            val ok = try {
                DocumentsContract.deleteDocument(resolver, item.uri)
            } catch (e: Exception) {
                Log.w(TAG, "Xóa thất bại: ${item.name}", e)
                false
            }
            if (ok) {
                deleted++
                bytes += item.size
            } else {
                errors.add("Xóa thất bại: ${item.name}")
            }
        }
        return Result(items.size, deleted, bytes, errors, dryRun = false)
    }

    private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "ts", "m4v")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

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
        startDocId: String? = null,
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

        // Điểm bắt đầu quét. Grant của SAF là "prefix", nên từ tree cấp trên
        // (ví dụ primary:Android/data) có thể nhảy thẳng xuống một thư mục con cụ thể
        // thay vì phải quét toàn bộ cây — tránh đụng vào dữ liệu của app khác.
        val rootDocId = startDocId ?: DocumentsContract.getTreeDocumentId(treeUri)

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
     * Quét (và tùy chọn xóa) file khớp đuôi theo ĐƯỜNG DẪN TUYỆT ĐỐI, dùng java.io.File.
     *
     * Chỉ dùng được cho thư mục thường (DCIM, Download...). Với `Android/data` của app khác
     * thì luôn thất bại — [diagnose] sẽ chỉ người dùng sang đường SAF.
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
        val path = normalizePath(absolutePath)
        val root = resolveListableDir(path)
            ?: return Result(0, 0, 0L, listOf(diagnose(path)), dryRun)

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

    /**
     * Chuẩn hóa đường dẫn người dùng nhập về dạng thật `/storage/emulated/0/...`.
     *
     * Các dạng viết tắt hay gặp (`/sdcard`, `/mnt/sdcard`, `/mnt/0`) không phải đường dẫn hợp lệ
     * khi truy cập bằng java.io.File trên Android 11, nên phải map lại trước khi dùng.
     */
    fun normalizePath(input: String): String {
        var p = input.trim().replace('\\', '/')
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        if (p.isEmpty()) return p

        val aliases = listOf(
            "/mnt/0", "/mnt/sdcard", "/sdcard", "/storage/self/primary", "/mnt/user/0/primary"
        )
        for (a in aliases) {
            if (p == a) return PRIMARY_ROOT
            if (p.startsWith("$a/")) return PRIMARY_ROOT + p.removePrefix(a)
        }
        return p
    }

    /**
     * Điểm vào duy nhất để quét/dọn theo một đường dẫn tuyệt đối.
     *
     * Thứ tự ưu tiên:
     *  1. Nếu đã có SAF grant phủ đường dẫn này -> dùng SAF. Đây là cách DUY NHẤT đọc được
     *     `Android/data` của app khác trên Android 11 (đã đối chiếu với ES File Explorer:
     *     nó giữ grant trên `primary:Android/data`).
     *  2. Không thì dùng java.io.File — đủ cho các thư mục thường như DCIM, Download.
     */
    fun clean(
        context: Context,
        absolutePath: String,
        extensions: List<String>,
        recursive: Boolean,
        dryRun: Boolean
    ): Result {
        val path = normalizePath(absolutePath)
        findGrant(context, path)?.let { (treeUri, docId) ->
            return run(
                context = context,
                treeUri = treeUri,
                extensions = extensions,
                recursive = recursive,
                dryRun = dryRun,
                startDocId = docId
            )
        }
        return runPath(path, extensions, recursive, dryRun)
    }

    /**
     * `/storage/emulated/0/Android/data/x` -> `primary:Android/data/x`.
     * Trả null nếu đường dẫn không nằm trong bộ nhớ trong (ví dụ thẻ SD).
     */
    fun docIdForPath(path: String): String? {
        val p = normalizePath(path)
        if (p == PRIMARY_ROOT) return "primary:"
        if (!p.startsWith("$PRIMARY_ROOT/")) return null
        return "primary:" + p.removePrefix("$PRIMARY_ROOT/")
    }

    /**
     * URI để mở sẵn picker ngay tại thư mục đích.
     *
     * Cần thiết vì picker của Android 11 ẩn `Android/data` khi người dùng bấm duyệt từng cấp,
     * nhưng nếu mở thẳng vào đó thì nút "Sử dụng thư mục này" vẫn cấp quyền bình thường.
     */
    fun initialPickerUri(path: String): Uri? {
        val docId = docIdForPath(path) ?: return null
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
    }

    /** Ngược của [docIdForPath]: `primary:Android/data/x` -> `/storage/emulated/0/Android/data/x`. */
    fun pathForDocId(docId: String): String? {
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:").trim('/')
        return if (rel.isEmpty()) PRIMARY_ROOT else "$PRIMARY_ROOT/$rel"
    }

    /** Grant của SAF là dạng prefix, nên tree ở cấp trên vẫn phủ được thư mục con. */
    private fun covers(treeDocId: String, docId: String): Boolean {
        if (treeDocId == docId) return true
        val base = if (treeDocId.endsWith(":")) treeDocId else "$treeDocId/"
        return docId.startsWith(base)
    }

    /**
     * Liệt kê file khớp cho thư mục đã cấu hình, tự tìm SAF grant.
     * Trả về null nếu chưa có grant (màn chính sẽ hiện trạng thái rỗng, yêu cầu cấp quyền).
     */
    fun listConfiguredFiles(
        context: Context,
        absolutePath: String,
        extensions: List<String>,
        recursive: Boolean,
        onItem: ((MediaItem) -> Unit)? = null
    ): List<MediaItem>? {
        val path = normalizePath(absolutePath)
        val (treeUri, docId) = findGrant(context, path) ?: return null
        return listFiles(context, treeUri, extensions, recursive, docId, onItem)
    }

    /** Tìm trong các grant đã lưu một cái phủ được [path]. */
    fun findGrant(context: Context, path: String): Pair<Uri, String>? {
        val docId = docIdForPath(path) ?: return null
        for (perm in context.contentResolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            val uri = perm.uri
            if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) continue
            val treeDocId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e: Exception) {
                continue
            }
            if (covers(treeDocId, docId)) return uri to docId
        }
        return null
    }

    /**
     * Trả về thư mục nếu nó vừa tồn tại, là thư mục, VÀ liệt kê được.
     *
     * Phải kiểm tra tới mức `list() != null` chứ không chỉ `exists()`: với `Android/data`,
     * cả `exists()` và `isDirectory()` đều trả true nhờ bit `--x` (đi xuyên qua được),
     * nhưng liệt kê thì thất bại.
     */
    private fun resolveListableDir(path: String): java.io.File? {
        val f = java.io.File(path)
        return if (f.isDirectory && f.list() != null) f else null
    }

    /** Phân biệt rõ "không tồn tại" với "tồn tại nhưng không đọc được", kèm gợi ý xử lý. */
    private fun diagnose(path: String): String {
        val target = java.io.File(path)

        if (!target.isDirectory) {
            var cur: java.io.File? = target.parentFile
            while (cur != null && !cur.isDirectory) cur = cur.parentFile
            val sb = StringBuilder("Thư mục không tồn tại: $path")
            if (cur != null) {
                sb.append("\nCha gần nhất còn thấy: ${cur.absolutePath}")
                cur.list()?.let { kids ->
                    sb.append("\nBên trong đó có: ${kids.sorted().take(15).joinToString(", ")}")
                }
            }
            return sb.toString()
        }

        return buildString {
            appendLine("Thư mục có tồn tại nhưng không liệt kê được: $path")
            appendLine()
            append("Android 11 chặn đọc Android/data bằng đường dẫn thường. ")
            append("Bấm \"Chọn thư mục (SAF)\" để cấp quyền cho đúng thư mục này, rồi quét lại.")
        }
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
