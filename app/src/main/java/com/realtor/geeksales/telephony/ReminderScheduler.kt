package com.realtor.geeksales.telephony

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.realtor.geeksales.MainActivity
import com.realtor.geeksales.data.db.FollowUpDao
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跟进提醒排程：登记跟进时写入 remindAt 后调用 [scheduleNext]，
 * 用非精确闹钟（setAndAllowWhileIdle，无需 SCHEDULE_EXACT_ALARM 特殊授权）排定下一条提醒。
 * 通知渠道在首次使用时创建。
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val followUpDao: FollowUpDao
) {
    companion object {
        private const val CHANNEL_ID = "tma_reminder"
        private const val CHANNEL_NAME = "跟进提醒"
        const val REQUEST_CODE = 0x544D41 // "TMA"
        const val EXTRA_REMIND_AT = "remind_at"
        const val EXTRA_CUSTOMER_NAME = "customer_name"
        const val NOTIFICATION_ID = 1001
    }

    /** 创建通知渠道（幂等，首次启动/提醒前调用） */
    fun ensureChannel() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "客户下次跟进时间提醒"
            }
            nm.createNotificationChannel(ch)
        }
    }

    /** 排定下一条未到期的跟进提醒；无待提醒时取消已有闹钟 */
    fun scheduleNext() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val next = followUpDao.nextReminder(System.currentTimeMillis())
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = reminderPendingIntent()
                if (next == null) {
                    am.cancel(pi)
                    return@runCatching
                }
                val intent = Intent(ctx, ReminderReceiver::class.java).apply {
                    putExtra(EXTRA_REMIND_AT, next.remindAt)
                    putExtra(EXTRA_CUSTOMER_NAME, next.customerName)
                }
                val pending = PendingIntent.getBroadcast(
                    ctx, REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                // 非精确闹钟：省电且无需 SCHEDULE_EXACT_ALARM 权限（Android 14+ 默认拒绝该权限）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.remindAt, pending)
            }
        }
    }

    fun reminderPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    /** 触发时展示通知 */
    fun showNotification(remindAt: Long, customerName: String) {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (customerName.isNotBlank()) "该跟进客户了：$customerName" else "有客户待跟进"
        val text = "下次跟进时间 ${Formatter.full(remindAt)}，点开查看详情。"
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            // 通知小图标必须是纯 alpha 单色；直接用系统铃声图标避免白块
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}
