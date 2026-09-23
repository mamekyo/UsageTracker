package com.mamekyo.usagetracker.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mamekyo.usagetracker.domain.UsageRepository
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UsageRepository.get(applicationContext).refreshAll()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "usage_refresh_periodic"
        private const val ONE_SHOT = "usage_refresh_now"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** (Re)schedules background refresh; WorkManager enforces a 15 minute minimum. */
        fun schedule(context: Context, minutes: Int) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * User-requested refresh. No network constraint: it must run promptly so the refreshing state shown in
         * widgets always ends; offline it finishes quickly with a connection error instead.
         */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<RefreshWorker>().build())
        }
    }
}
