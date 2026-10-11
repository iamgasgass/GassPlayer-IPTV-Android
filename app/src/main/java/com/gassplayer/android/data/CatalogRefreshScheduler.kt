package com.gassplayer.android.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Collega realmente le impostazioni "Aggiornamento automatico catalogo" alla
 * sincronizzazione del catalogo. WorkManager garantisce la persistenza del job
 * anche quando l'app non è in primo piano.
 */
object CatalogRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "gassplayer_catalog_refresh"
    private const val TAG = "catalog-refresh"

    suspend fun sync(context: Context, prefs: AppPreferences, settings: AppSettings) {
        val interval = RefreshInterval.from(settings.catalogRefreshInterval)
        val wm = WorkManager.getInstance(context)

        if (interval.millis == null) {
            wm.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val repeatMs = max(interval.millis!!, 15 * 60_000L)
        val last = prefs.lastRefreshFlow().first()
        val initialDelay = last?.let {
            (interval.millis!! - (System.currentTimeMillis() - it)).coerceAtLeast(0L)
        } ?: 0L

        val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(
            repeatMs, TimeUnit.MILLISECONDS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .addTag(TAG)
            .build()

        wm.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class CatalogRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? com.gassplayer.android.GassPlayerApplication
            ?: return Result.failure()

        return runCatching {
            val settings = app.prefs.settingsFlow.first()
            val interval = RefreshInterval.from(settings.catalogRefreshInterval)
            val last = app.prefs.lastRefreshFlow().first()

            if (interval.millis == null) return Result.success()

            val due = last == null || System.currentTimeMillis() - last >= interval.millis
            if (!due) return Result.success()

            val result = app.catalog.loadAll(force = true)

            // Non spostare lastRefresh in avanti quando il catalogo è incompleto:
            // una successiva esecuzione potrà ritentare senza perdere il trigger.
            if (result.errors.isEmpty()) {
                app.prefs.saveLastRefresh(System.currentTimeMillis())
                Result.success()
            } else {
                app.diagnostics.log(
                    "WARN",
                    "catalog",
                    "Aggiornamento automatico parziale: ${result.errors.joinToString(" | ")}"
                )
                Result.retry()
            }
        }.getOrElse {
            app.diagnostics.log("ERROR", "catalog", "Aggiornamento automatico fallito: ${it.message}")
            Result.retry()
        }
    }
}
