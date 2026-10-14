package com.sacls.foldercleaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Load thumbnail ẢNH từ content:// URI, có:
 *  - Cache LRU theo 1/8 bộ nhớ heap -> không giữ quá nhiều bitmap, tránh OOM.
 *  - Decode downsample về đúng kích thước ô (inSampleSize) -> ảnh gốc nhiều MP vẫn nhẹ.
 *  - Hủy job cũ khi ImageView bị tái sử dụng lúc scroll -> không set nhầm ảnh, không rò.
 *
 * Chỉ dùng cho ảnh. Video hiển thị icon, không decode (rẻ và không rủi ro).
 */
class ThumbLoader(private val context: Context, private val scope: CoroutineScope) {

    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxKb / 8) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // ImageView -> job đang chạy, để hủy khi bind lại.
    private val jobs = HashMap<ImageView, Job>()

    private val targetPx = (context.resources.displayMetrics.density * 150).toInt().coerceAtLeast(120)

    fun load(uri: Uri, into: ImageView) {
        val key = uri.toString()
        cache.get(key)?.let {
            jobs.remove(into)?.cancel()
            into.setImageBitmap(it)
            return
        }

        jobs.remove(into)?.cancel()
        into.setImageDrawable(null)
        into.tag = key

        val job = scope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(uri) }
            // Chỉ set nếu ImageView vẫn đang dành cho URI này (chống nhầm khi recycle).
            if (bmp != null && into.tag == key) {
                cache.put(key, bmp)
                into.setImageBitmap(bmp)
            }
        }
        jobs[into] = job
    }

    fun cancel(into: ImageView) {
        jobs.remove(into)?.cancel()
        into.tag = null
    }

    private fun decode(uri: Uri): Bitmap? {
        return try {
            // Pass 1: đọc kích thước thật.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Pass 2: decode downsample.
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                inPreferredConfig = Bitmap.Config.RGB_565 // nhẹ RAM hơn ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            null
        }
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var hw = w / 2
        var hh = h / 2
        while (hw >= target && hh >= target) {
            sample *= 2
            hw /= 2
            hh /= 2
        }
        return sample
    }
}
