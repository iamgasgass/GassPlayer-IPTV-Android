package com.gassplayer.android.media

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gassplayer.android.GassPlayerApplication
import com.gassplayer.android.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class GassPlayerMediaService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()
    override fun onCreate() {
        super.onCreate()
        val controller = (application as GassPlayerApplication).playback
        session = MediaSession.Builder(this, controller.player).setId("GassPlayerServiceSession").build()
        // Il motore attivo puo' cambiare (ExoPlayer <-> VLC, o ExoPlayer ricreato): la sessione lo segue.
        scope.launch {
            controller.playerEpoch.collect {
                val active = controller.player
                session?.let { current -> if (current.player !== active) runCatching { current.player = active } }
            }
        }
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = NotificationCompat.Builder(this, "gassplayer")
            .setSmallIcon(com.gassplayer.android.R.drawable.gass_stat_clapper)
            .setContentTitle("GassPlayer IPTV")
            .setContentText("Riproduzione multimediale attiva")
            .setContentIntent(launch)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        startForeground(42, notification)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { scope.cancel(); session?.release(); session = null; super.onDestroy() }
}
