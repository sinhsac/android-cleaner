package com.sacls.foldercleaner

import android.content.Context

/**
 * Lưu cấu hình app bằng SharedPreferences.
 * - absolutePath: thư mục cần dọn — đây là mục tiêu duy nhất.
 * - treeUri: grant SAF gần nhất, chỉ để tham khảo. Nguồn sự thật khi chạy là
 *   `contentResolver.persistedUriPermissions` (xem [CleanerEngine.findGrant]).
 * - extensions: danh sách đuôi file cần xóa, phân tách bằng dấu phẩy.
 * - recursive: có quét thư mục con hay không.
 * - moveDestUri: thư mục đích (thẻ SD) cho tính năng di chuyển DCIM.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("folder_cleaner_prefs", Context.MODE_PRIVATE)

    var treeUri: String?
        get() = sp.getString(KEY_TREE_URI, null)
        set(value) = sp.edit().putString(KEY_TREE_URI, value).apply()

    /** Thư mục cần dọn, dạng đường dẫn tuyệt đối. */
    var absolutePath: String
        get() = sp.getString(KEY_ABS_PATH, DEFAULT_PATH) ?: DEFAULT_PATH
        set(value) = sp.edit().putString(KEY_ABS_PATH, value).apply()

    var extensionsRaw: String
        get() = sp.getString(KEY_EXTENSIONS, DEFAULT_EXTENSIONS) ?: DEFAULT_EXTENSIONS
        set(value) = sp.edit().putString(KEY_EXTENSIONS, value).apply()

    var recursive: Boolean
        get() = sp.getBoolean(KEY_RECURSIVE, true)
        set(value) = sp.edit().putBoolean(KEY_RECURSIVE, value).apply()

    /** URI tree nguồn cho tính năng di chuyển DCIM/Camera. */
    var moveSrcUri: String?
        get() = sp.getString(KEY_MOVE_SRC, null)
        set(value) = sp.edit().putString(KEY_MOVE_SRC, value).apply()

    /** URI tree đích (thẻ SD) cho tính năng di chuyển DCIM/Camera. */
    var moveDestUri: String?
        get() = sp.getString(KEY_MOVE_DEST, null)
        set(value) = sp.edit().putString(KEY_MOVE_DEST, value).apply()

    /**
     * Chuẩn hóa danh sách đuôi file: bỏ khoảng trắng, bỏ dấu chấm đầu, chữ thường, loại trùng/rỗng.
     * Ví dụ: "mp4, .JPG ,," -> ["mp4", "jpg"]
     */
    fun parsedExtensions(): List<String> =
        extensionsRaw.split(",")
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        const val DEFAULT_EXTENSIONS = "mp4,jpg"
        /**
         * Thư mục KBVISION VIEW Plus lưu video/ảnh tải về.
         * Xác nhận trực tiếp trên máy qua adb (SM-A507FN, Android 11):
         * package thật là `com.mm.android.direct.KBVISIONVIEWPlus` và thư mục con
         * là `Download` (không có "s").
         */
        const val DEFAULT_PATH =
            "/storage/emulated/0/Android/data/com.mm.android.direct.KBVISIONVIEWPlus/files/Download"

        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_ABS_PATH = "abs_path"
        private const val KEY_EXTENSIONS = "extensions"
        private const val KEY_RECURSIVE = "recursive"
        private const val KEY_MOVE_SRC = "move_src_uri"
        private const val KEY_MOVE_DEST = "move_dest_uri"
    }
}
