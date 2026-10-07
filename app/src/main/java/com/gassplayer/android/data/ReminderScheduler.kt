package com.gassplayer.android.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ReminderScheduler(private val context: Context) {
    fun schedule(program: EpgProgram) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, EpgReminderReceiver::class.java).putExtra("title", program.title).putExtra("description", program.description.orEmpty())
        val pending = PendingIntent.getBroadcast(context, program.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
            alarm.set(AlarmManager.RTC_WAKEUP, program.startMs, pending)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, program.startMs, pending)
        }
    }
}

class EpgReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Programma TV"; val description = intent.getStringExtra("description").orEmpty()
        val notification = NotificationCompat.Builder(context, "gassplayer").setSmallIcon(com.gassplayer.android.R.drawable.ic_gassplayer).setContentTitle(title).setContentText(description).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(title.hashCode(), notification) }
    }
}
