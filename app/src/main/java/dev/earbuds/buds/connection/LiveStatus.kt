package dev.earbuds.buds.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.earbuds.buds.MainActivity
import dev.earbuds.buds.R

class LiveStatus(private val context: Context) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Earbud live status",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description="Connection, battery and listening mode while your earbuds are connected"
            setSound(null,null); enableVibration(false)
        })
    }
    fun notification(state: BudsState): Notification {
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disconnect=PendingIntent.getService(context,1,
            Intent(context,EarbudsService::class.java).setAction(EarbudsService.DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snapshot=state.snapshot
        val title=if (state.phase==ConnectionPhase.CONNECTING) "Connecting to your earbuds" else "Redmi Buds 6 Pro"
        val left=snapshot?.info?.left?.percent
        val right=snapshot?.info?.right?.percent
        val battery=listOfNotNull(left,right).average().takeUnless { it.isNaN() }?.toInt() ?: 0
        val status=snapshot?.let {
            "L ${left?.toString() ?: "—"}% · R ${right?.toString() ?: "—"}% · Case ${it.info.case?.percent ?: "—"}% · ${it.mode.title}"
        } ?: "Establishing a secure Bluetooth control session"
        val builder=NotificationCompat.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(title).setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0,"Disconnect",disconnect)
            .setRequestPromotedOngoing(true)
        if (Build.VERSION.SDK_INT>=36) {
            builder.setShortCriticalText(if (snapshot==null) "Buds" else "$battery%")
            builder.setStyle(NotificationCompat.ProgressStyle()
                .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(Color.rgb(53,107,87))))
                .setProgress(battery).setProgressIndeterminate(snapshot==null))
        } else builder.setProgress(100,battery,snapshot==null)
        return builder.build()
    }
    fun update(state: BudsState) { manager.notify(ID,notification(state)) }
    companion object { const val CHANNEL="buds_live_status"; const val ID=76 }
}
