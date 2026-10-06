package com.iamgasgass.gassplayer.services
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
class VodDownloadManager(private val context:Context){fun enqueue(url:String,title:String):Long{val safe=title.replace(Regex("[^A-Za-z0-9._ -]"),"_");val request=DownloadManager.Request(Uri.parse(url)).setTitle(title).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(context,Environment.DIRECTORY_MOVIES,"$safe.mp4").setAllowedOverMetered(true);return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)};fun cancel(id:Long)=(context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)}
