package com.sacls.foldercleaner

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Lên lịch / hủy lịch dọn tự động bằng WorkManager.
 * WorkManager yêu cầu chu kỳ tối thiểu 15 phút; ở đây dùng đơn vị giờ nên luôn hợp lệ.
 */
object CleanupScheduler {

    private const val WORK_NAME = "periodic_cleanup"

    fun schedule(context: Context, intervalHours: Int) {
        val hours = intervalHours.coerceAtLeast(1).toLong()
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(hours, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE để đổi chu kỳ mà không mất lịch cũ.
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
