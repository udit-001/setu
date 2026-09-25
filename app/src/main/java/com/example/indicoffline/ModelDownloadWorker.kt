package com.example.indicoffline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelUrls = inputData.getStringArray("MODEL_URLS") ?: return@withContext Result.failure()
        val modelFilename = inputData.getString("MODEL_FILENAME") ?: return@withContext Result.failure()

        createNotificationChannel()

        setForeground(createForegroundInfo(0))

        val dest = inputData.getString("DEST")?.let { File(it) }
            ?: File(applicationContext.filesDir, modelFilename)
        val success = ModelFetcher.download(
            dest = dest,
            urls = modelUrls.toList(),
            onProgress = { progress ->
                setProgress(workDataOf("PROGRESS" to progress))
                setForeground(createForegroundInfo(progress))
            }
        )

        if (success) Result.success() else Result.failure()
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val title = "Setting up your offline experience"
        val cancel = "Cancel"

        val intent = androidx.work.WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, "download_channel")
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(if (progress < 100) "$progress% completed" else "Finalizing...")
            .setSmallIcon(R.drawable.ic_setu_logo)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, cancel, intent)
            .setProgress(100, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use this worker's id as the notification id so concurrent
            // workers (e.g. ASR model + tokens) don't collide.
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "download_channel",
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}
