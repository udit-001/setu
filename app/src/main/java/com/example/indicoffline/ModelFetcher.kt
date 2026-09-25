package com.example.indicoffline

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Downloads a single file, trying a list of mirror URLs in turn with
 * resume support. The destination file only ever appears atomically
 * (written to `<dest>.tmp`, then renamed).
 *
 * Reports progress as a percentage (0-100) through [onProgress].
 */
interface ModelFetchFn {
    suspend fun download(
        dest: File,
        urls: List<String>,
        onProgress: suspend (Int) -> Unit = {},
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS
    ): Boolean

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 50
        const val DEFAULT_INITIAL_RETRY_DELAY_MS = 5000L
    }
}

object ModelFetcher : ModelFetchFn {

    override suspend fun download(
        dest: File,
        urls: List<String>,
        onProgress: suspend (Int) -> Unit,
        maxAttempts: Int,
        initialRetryDelayMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        require(urls.isNotEmpty()) { "ModelFetcher.download requires at least one URL" }

        var attempts = 0
        var delayMs = initialRetryDelayMs
        var urlIndex = 0

        while (attempts < maxAttempts) {
            val url = urls[urlIndex]
            if (tryDownload(url, dest, onProgress)) {
                onProgress(100)
                return@withContext true
            }
            urlIndex = (urlIndex + 1) % urls.size
            attempts++
            android.util.Log.d("ModelFetcher", "Retrying with $url... attempt $attempts")
            delay(delayMs)
            delayMs = minOf(delayMs * 2, 60000L)
        }
        false
    }

    private suspend fun tryDownload(urlToDownload: String, dest: File, onProgress: suspend (Int) -> Unit): Boolean {
        try {
            val tempFile = File(dest.parentFile, "${dest.name}.tmp")
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val url = URL(urlToDownload)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
                android.util.Log.d("ModelFetcher", "Resuming from $existingBytes bytes")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != 206) {
                android.util.Log.e("ModelFetcher", "Bad response: $responseCode")
                return false
            }

            val contentLength = connection.contentLengthLong
            val resuming = existingBytes > 0 && responseCode == 206
            val totalBytes = if (resuming) existingBytes + contentLength else contentLength

            connection.inputStream.use { input ->
                val output = if (resuming) {
                    FileOutputStream(tempFile, true)
                } else {
                    tempFile.outputStream()
                }
                output.use { out ->
                    val buffer = ByteArray(65536)
                    var downloaded = if (resuming) existingBytes else 0L
                    var bytes: Int
                    var lastLoggedProgress = -1
                    var lastLogTime = 0L

                    while (input.read(buffer).also { bytes = it } != -1) {
                        out.write(buffer, 0, bytes)
                        downloaded += bytes

                        if (totalBytes > 0) {
                            val progress = ((downloaded.toDouble() / totalBytes.toDouble()) * 100).toInt()
                            val now = System.currentTimeMillis()
                            if (progress != lastLoggedProgress || now - lastLogTime > 2000) {
                                lastLoggedProgress = progress
                                lastLogTime = now
                                android.util.Log.d("ModelFetcher", "Downloaded: $downloaded / $totalBytes bytes ($progress%)")
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            val renamed = tempFile.renameTo(dest)
            if (!renamed) {
                android.util.Log.e("ModelFetcher", "Failed to rename temp file")
                return false
            }
            return true
        } catch (e: Exception) {
            android.util.Log.e("ModelFetcher", "Download failed: ${e.message}")
            return false
        }
    }
}
