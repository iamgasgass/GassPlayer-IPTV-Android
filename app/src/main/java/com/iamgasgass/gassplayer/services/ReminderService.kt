package com.iamgasgass.gassplayer.services
import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
class ReminderService(private val context:Context){fun schedule(id:Int,title:String,atMillis:Long){val i=Intent(context,ReminderReceiver::class.java).putExtra("title",title).putExtra("id",id);val p=PendingIntent.getBroadcast(context,id,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,atMillis,p)}}
class ReminderReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val channel="epg_reminders";val nm=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;if(android.os.Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel(channel,"Promemoria EPG",NotificationManager.IMPORTANCE_HIGH));nm.notify(i.getIntExtra("id",0),NotificationCompat.Builder(c,channel).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(i.getStringExtra("title")?:"Programma in onda").setContentText("Sta per iniziare").setAutoCancel(true).build())}}
