package com.realtor.geeksales

import android.app.Application
import com.realtor.geeksales.telephony.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GeekSalesApp : Application() {

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("TMA_Crash", "Uncaught exception on $thread", throwable)
        }
        // 启动即恢复跟进提醒排程（应用可能数天未打开，闹钟必须重建）
        reminderScheduler.ensureChannel()
        reminderScheduler.scheduleNext()
    }
}
