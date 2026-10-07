package com.gassplayer.android.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.net.URL

class DownloadRepository(private val context: Context) {
    fun enqueue(item: MediaItem, wifiOnly: Boolean): String {
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>().setInputData(workDataOf("url" to item.streamUrl, "id" to item.id, "title" to item.title)).setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).addTag("gass_download").build()
        WorkManager.getInstance(context).enqueue(request); return request.id.toString()
    }
    fun outputDir(): File = context.filesDir.resolve("downloads").also { it.mkdirs() }
}

class MediaDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return androidx.work.ListenableWorker.Result.failure()
        val id = inputData.getString("id") ?: return androidx.work.ListenableWorker.Result.failure()
        val out = applicationContext.filesDir.resolve("downloads").also { it.mkdirs() }.resolve("$id.mp4")
        return try {
            val connection = URL(url).openConnection().apply { connectTimeout = 12_000; readTimeout = 60_000; useCaches = false }
            connection.getInputStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    val length = connection.contentLengthLong
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (length > 0) setProgress(workDataOf("progress" to (done.toDouble() / length.toDouble()).coerceIn(0.0, 1.0)))
                    }
                }
            }
            androidx.work.ListenableWorker.Result.success(workDataOf("file" to out.absolutePath))
        } catch (_: Throwable) {
            if (runAttemptCount < 3) androidx.work.ListenableWorker.Result.retry() else androidx.work.ListenableWorker.Result.failure()
        }
    }
}

fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel("gassplayer", "GassPlayer", NotificationManager.IMPORTANCE_LOW))
}
