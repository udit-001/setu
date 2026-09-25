package com.example.indicoffline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/**
 * [ModelFetchFn] adapter that runs the download through WorkManager, so
 * large files survive process death and network changes. Progress is
 * forwarded to [onProgress]; the call suspends until the work reaches a
 * terminal state.
 *
 * Retry behaviour: the worker itself retries each URL with backoff
 * (see [ModelFetcher]). This adapter additionally re-enqueues the work a
 * bounded number of times if the whole run fails, always with
 * [ExistingWorkPolicy.REPLACE] so a previously failed work record can
 * never stick. Re-enqueues resume from the partial `.tmp` file.
 */
class WorkManagerModelFetcher(context: Context) : ModelFetchFn {

    private val appContext = context.applicationContext

    override suspend fun download(
        dest: File,
        urls: List<String>,
        onProgress: suspend (Int) -> Unit,
        maxAttempts: Int,
        initialRetryDelayMs: Long
    ): Boolean {
        val workManager = WorkManager.getInstance(appContext)
        val workName = "model_fetch_${dest.absolutePath.hashCode()}"
        // The worker owns URL retry/backoff; this loop only bounds
        // re-enqueue attempts after a fully failed run.
        val reEnqueueAttempts = 3

        var delayMs = initialRetryDelayMs
        repeat(reEnqueueAttempts) { attempt ->
            if (attempt > 0) delay(delayMs)
            delayMs = minOf(delayMs * 2, 60000L)

            workManager.enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setInputData(
                        workDataOf(
                            "DEST" to dest.absolutePath,
                            "MODEL_FILENAME" to dest.name,
                            "MODEL_URLS" to urls.toTypedArray()
                        )
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag("model_download")
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            )

            val terminal = workManager.getWorkInfosForUniqueWorkFlow(workName)
                .mapNotNull { infos -> infos.firstOrNull() }
                .onEach { info ->
                    if (!info.state.isFinished) {
                        val pct = info.progress.getInt("PROGRESS", 0)
                        if (pct > 0) onProgress(pct)
                    }
                }
                .first { it.state.isFinished }

            when (terminal.state) {
                WorkInfo.State.SUCCEEDED -> {
                    onProgress(100)
                    return true
                }
                else -> {
                    android.util.Log.d(
                        "WorkManagerModelFetcher",
                        "Download work for '${dest.name}' ended in ${terminal.state} (attempt ${attempt + 1})"
                    )
                }
            }
        }
        return false
    }
}
