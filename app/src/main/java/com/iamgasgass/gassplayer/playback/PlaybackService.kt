package com.iamgasgass.gassplayer.playback
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
class PlaybackService:MediaSessionService(){private var session:MediaSession?=null;override fun onCreate(){super.onCreate();session=MediaSession.Builder(this,ExoPlayer.Builder(this).build()).build()}override fun onGetSession(controllerInfo:MediaSession.ControllerInfo)=session;override fun onDestroy(){session?.run{player.release();release()};session=null;super.onDestroy()}}
