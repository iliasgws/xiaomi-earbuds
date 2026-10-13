package dev.earbuds.buds.connection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.earbuds.buds.R
import dev.earbuds.buds.protocol.NoiseMode

class LiveStatus(private val context: Context) {
    private val channel=NotificationChannel(CHANNEL,"Earbud live status",NotificationManager.IMPORTANCE_LOW)
        .apply { setShowBadge(false) }

    init {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun notification(state: BudsState): Notification {
        if (ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)
            !=PackageManager.PERMISSION_GRANTED) return NotificationCompat.Builder(context,CHANNEL).build()
        val snapshot=state.snapshot
        val left=snapshot?.info?.left?.percent
        val right=snapshot?.info?.right?.percent
        val mode=snapshot?.mode
        val battery=when {
            left!=null&&right!=null -> (left+right)/2
            left!=null -> left
            right!=null -> right
            else -> 0
        }
        val status=when {
            left==null&&right==null -> "Buds not reporting"
            left==null -> "L · R ${right}%"
            right==null -> "L ${left}% · R"
            else -> "L ${left}% · R ${right}%"
        }
        val title=if (state.phase==ConnectionPhase.CONNECTED) "Redmi Buds 6 Pro"
            else "Connecting to your earbuds"
        val builder=NotificationCompat.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_makealight_mono)
            .setContentTitle(title)
            .setContentText(status)
            .setSubText(mode?.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
        if (snapshot!=null&&state.phase==ConnectionPhase.CONNECTED) {
            for (m in NoiseMode.entries) {
                val intent=Intent(context,EarbudsService::class.java)
                    .setAction(EarbudsService.NOISE)
                    .putExtra(EarbudsService.NOISE_MODE,m.code)
                val pending=PendingIntent.getService(context,200+m.code,intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0,m.title,pending)
            }
        }
        if (Build.VERSION.SDK_INT>=36) {
            val critical=when {
                left==null&&right!=null -> "$right%"
                right==null&&left!=null -> "$left%"
                left!=null&&right!=null -> "$left%·$right%"
                else -> "—"
            }
            builder.setShortCriticalText(critical)
            val style=NotificationCompat.ProgressStyle().setProgressIndeterminate(false)
            val green=android.graphics.Color.parseColor("#356B57")
            if (left!=null&&right!=null) {
                style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(left).setColor(green))
                style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(right).setColor(green))
            } else {
                style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(battery).setColor(green))
            }
            builder.setStyle(style)
        } else {
            builder.setProgress(100,battery,snapshot==null)
        }
        return builder.build()
    }

    fun update(state: BudsState) {
        if (ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)
            !=PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(ID,notification(state))
    }

    companion object {
        const val CHANNEL="live"
        const val ID=1
    }
}
