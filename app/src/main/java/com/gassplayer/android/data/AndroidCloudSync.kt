package com.gassplayer.android.data

import android.app.backup.BackupManager
import android.content.Context
import kotlinx.coroutines.flow.first

/** Android equivalent of the iOS iCloud key-value layer.
 * Auto Backup provides account-backed restoration; manual JSON export remains the deterministic migration path.
 */
class AndroidCloudSync(private val context: Context, private val prefs: AppPreferences) {
    fun requestBackup() { BackupManager(context).dataChanged() }
    suspend fun snapshot(): CloudSnapshot = CloudSnapshot(prefs.sourcesFlow.first(), prefs.favoritesFlow.first(), prefs.watchFlow.first())
}

@kotlinx.serialization.Serializable
data class CloudSnapshot(val sources: List<MediaSourceConfig>, val favorites: FavoriteState, val watch: List<WatchEntry>)
