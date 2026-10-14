package com.sacls.foldercleaner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker chạy định kỳ: đọc cấu hình từ Prefs, dọn file khớp đuôi trong thư mục đã chọn,
 * rồi hiện thông báo kết quả.
 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val uriStr = prefs.treeUri ?: return@withContext Result.success()
        val exts = prefs.parsedExtensions()
        if (exts.isEmpty()) return@withContext Result.success()

        val treeUri = Uri.parse(uriStr)

        // Kiểm tra quyền trên URI còn hiệu lực không (người dùng có thể đã thu hồi).
        val stillGranted = applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
        if (!stillGranted) {
            notify(
                applicationContext.getString(R.string.notif_title),
                applicationContext.getString(R.string.notif_permission_lost)
            )
            return@withContext Result.success()
        }

        val result = CleanerEngine.run(
            context = applicationContext,
            treeUri = treeUri,
            extensions = exts,
            recursive = prefs.recursive,
            dryRun = false
        )

        val summary = applicationContext.getString(
            R.string.notif_summary,
            result.deletedCount,
            CleanerEngine.formatBytes(result.totalBytes)
        )
        prefs.lastResult = summary
        if (result.deletedCount > 0 || result.errors.isNotEmpty()) {
            notify(applicationContext.getString(R.string.notif_title), summary)
        }
        Result.success()
    }

    private fun notify(title: String, text: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // chưa được cấp quyền thông báo thì bỏ qua, không crash.
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "cleanup_channel"
        const val NOTIF_ID = 1001
    }
}
