package com.realtor.geeksales.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DialerHelper @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    companion object {
        private const val TAG = "DialerHelper"

        /** 归一化号码：仅保留数字 */
        fun normalize(phone: String): String = phone.filter { it.isDigit() }
    }

    /**
     * 最近一次通过本 App 拨出的号码（归一化）。
     * PhoneStateListener 的 incomingNumber 对呼出电话恒为空（Android 12+ 尤其如此），
     * 挂断登记需要用它兜底匹配客户，否则自己拨出的电话永远登记成"未知号码"。
     */
    @Volatile
    var lastDialedPhone: String = ""
        private set

    /**
     * 使用 ACTION_DIAL 打开系统拨号盘（无需 CALL_PHONE 权限，但需用户点"拨打"）。
     * 此为首选，合规 & 稳妥。
     */
    fun openDialer(phone: String) {
        lastDialedPhone = normalize(phone)
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${normalize(phone)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
            .onFailure { Log.w(TAG, "openDialer failed", it) }
    }

    /** 直接发起 CALL：需要 Manifest.permission.CALL_PHONE 运行时授权。无权限时安全跳过（不发意图），由 UI 层引导授权。 */
    fun directCall(phone: String) {
        lastDialedPhone = normalize(phone)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.CALL_PHONE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "directCall skipped: CALL_PHONE not granted")
            return
        }
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:${normalize(phone)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
            .onFailure { Log.w(TAG, "directCall failed", it) }
    }

    /** 清空记录的拨出号码（挂断登记消费后调用，避免陈旧号码误匹配下一次通话） */
    fun clearLastDialed() {
        lastDialedPhone = ""
    }

    /** 当前是否处于通话中（仅粗略判断，读 READ_PHONE_STATE 时才准确） */
    fun isInCall(): Boolean {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        return tm?.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
    }

    fun defaultDialerPackage(): String? {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        return tm?.defaultDialerPackage
    }
}
