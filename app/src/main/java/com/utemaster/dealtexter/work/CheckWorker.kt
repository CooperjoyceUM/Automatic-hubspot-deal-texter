package com.utemaster.dealtexter.work

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
import androidx.work.workDataOf
import com.utemaster.dealtexter.Graph
import java.util.concurrent.TimeUnit

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val settings = Graph.settings(app)
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!settings.state.value.enabled && !manual) return Result.success()

        val config = settings.toConfig()
        if (config == null) {
            settings.recordRun("Not switched on yet – turn on automatic checking first")
            return Result.success()
        }

        val summary = Graph.processor(app).runCheck(config)
        settings.recordRun(summary.toString())
        return Result.success()
    }

    companion object {
        const val KEY_MANUAL = "manual"
    }
}

object CheckScheduler {
    private const val PERIODIC = "deal-check"
    private const val NOW = "deal-check-now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun periodic() = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()

    /** Called at app start: keeps the existing schedule if there is one. */
    fun ensureScheduled(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic())
    }

    fun start(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic())
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setInputData(workDataOf(CheckWorker.KEY_MANUAL to true))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}
