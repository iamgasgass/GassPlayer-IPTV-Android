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

@UnstableApi
class GassPlayerMediaService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val controller = (application as GassPlayerApplication).playback
        session = MediaSession.Builder(this, controller.player).setId("GassPlayerServiceSession").build()
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = NotificationCompat.Builder(this, "gassplayer")
            .setSmallIcon(com.gassplayer.android.R.drawable.ic_gassplayer)
            .setContentTitle("GassPlayer IPTV")
            .setContentText("Riproduzione multimediale attiva")
            .setContentIntent(launch)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        startForeground(42, notification)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
