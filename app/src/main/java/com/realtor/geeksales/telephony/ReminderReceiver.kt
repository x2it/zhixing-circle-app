package com.realtor.geeksales.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 跟进提醒广播接收器：AlarmManager 触发后展示通知，
 * 并立即重排下一条提醒（避免应用长期未打开导致后续提醒全部丢失）。
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val remindAt = intent.getLongExtra(ReminderScheduler.EXTRA_REMIND_AT, 0L)
        val name = intent.getStringExtra(ReminderScheduler.EXTRA_CUSTOMER_NAME).orEmpty()
        if (remindAt > 0L) {
            scheduler.showNotification(remindAt, name)
        }
        // 重排下一条；goAsync 保证异步完成前 Receiver 不被回收
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { scheduler.scheduleNext() }
            pending.finish()
        }
    }
}
