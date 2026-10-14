package com.sacls.foldercleaner

import android.content.Context

/**
 * Lưu cấu hình app bằng SharedPreferences.
 * - absolutePath: thư mục cần dọn — đây là mục tiêu duy nhất.
 * - treeUri: grant SAF gần nhất, chỉ để tham khảo. Nguồn sự thật khi chạy là
 *   `contentResolver.persistedUriPermissions` (xem [CleanerEngine.findGrant]).
 * - extensions: danh sách đuôi file cần xóa, phân tách bằng dấu phẩy.
 * - recursive: có quét thư mục con hay không.
 * - autoEnabled / autoIntervalHours: cấu hình dọn tự động.
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

    var autoEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_ENABLED, value).apply()

    var autoIntervalHours: Int
        get() = sp.getInt(KEY_AUTO_INTERVAL, DEFAULT_INTERVAL_HOURS)
        set(value) = sp.edit().putInt(KEY_AUTO_INTERVAL, value).apply()

    var lastResult: String?
        get() = sp.getString(KEY_LAST_RESULT, null)
        set(value) = sp.edit().putString(KEY_LAST_RESULT, value).apply()

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
        const val DEFAULT_INTERVAL_HOURS = 24
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
        private const val KEY_AUTO_ENABLED = "auto_enabled"
        private const val KEY_AUTO_INTERVAL = "auto_interval_hours"
        private const val KEY_LAST_RESULT = "last_result"
    }
}
