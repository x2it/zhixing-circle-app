package com.realtor.geeksales.viewmodel

import android.app.Application
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.FollowResult
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.telephony.CallObserver
import com.realtor.geeksales.telephony.DialerHelper
import com.realtor.geeksales.telephony.PostCallReceiver
import com.realtor.geeksales.telephony.ReminderScheduler
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PostCallPrompt(
    val customerId: Long,
    val customerName: String,
    val phone: String,
    val durationSec: Int
)

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    app: Application,
    private val repo: CustomerRepository,
    private val callObserver: CallObserver,
    private val postCallReceiver: PostCallReceiver,
    private val dialerHelper: DialerHelper,
    private val reminderScheduler: ReminderScheduler
) : AndroidViewModel(app) {

    private val _postCallPrompt = MutableStateFlow<PostCallPrompt?>(null)
    val postCallPrompt: StateFlow<PostCallPrompt?> = _postCallPrompt.asStateFlow()

    private var lastOffHookAt: Long = 0L
    private var lastPhoneNumber: String = ""

    init {
        // 启动迁移：v1.6.2 之前移除队列成员不重排，残留跳号数据（如 1,3,5）
        // 会导致名单页 Q# 与队列页位置编号对不上。启动时统一重排为 1..N。
        viewModelScope.launch {
            runCatching { repo.normalizeQueueOnLaunch() }
        }
        viewModelScope.launch {
            runCatching {
                callObserver.events()
                    .distinctUntilChanged { a, b -> a.state == b.state }
                    .collect { ev ->
                        when (ev.state) {
                            TelephonyManager.CALL_STATE_OFFHOOK -> {
                                lastOffHookAt = System.currentTimeMillis()
                                lastPhoneNumber = ev.phone
                            }
                            TelephonyManager.CALL_STATE_IDLE -> {
                                if (lastOffHookAt > 0L) {
                                    val durationSec =
                                        ((System.currentTimeMillis() - lastOffHookAt) / 1000L).toInt()
                                    lastOffHookAt = 0L
                                    PostCallReceiver.send(
                                        getApplication(),
                                        phone = lastPhoneNumber.ifBlank { ev.phone },
                                        durationSec = durationSec.coerceAtLeast(0)
                                    )
                                    lastPhoneNumber = ""
                                }
                            }
                        }
                    }
            }.onFailure { it.printStackTrace() }
        }
        viewModelScope.launch {
            runCatching {
                postCallReceiver.postCallEvents().collect { (phone, dur) ->
                    // 兜底：呼出电话的 incomingNumber 恒为空（Android 12+ 无 READ_CALL_LOG 时亦然），
                    // 用本 App 最近拨出的号码匹配客户，避免"自己拨的电话登记成未知号码"。
                    val norm = Formatter.normalizePhone(phone)
                        .ifBlank { dialerHelper.lastDialedPhone }
                    val c = if (norm.isNotBlank()) repo.getByPhoneNormalized(norm) else null
                    if (c != null) {
                        _postCallPrompt.value = PostCallPrompt(
                            customerId = c.id,
                            customerName = c.name,
                            phone = c.phone,
                            durationSec = dur
                        )
                    } else {
                        _postCallPrompt.value = PostCallPrompt(
                            customerId = 0L,
                            customerName = "未知号码",
                            phone = norm.ifBlank { phone },
                            durationSec = dur
                        )
                    }
                    // 消费后清空，避免陈旧号码误匹配下一次无关通话
                    dialerHelper.clearLastDialed()
                }
            }.onFailure { it.printStackTrace() }
        }
    }

    fun dismissPostCallPrompt() {
        _postCallPrompt.value = null
    }

    fun savePostCallFollowUp(
        result: FollowResult,
        note: String?,
        remindAt: Long?,
        phoneIfMissing: String? = null,
        nameIfMissing: String? = null
    ) = viewModelScope.launch {
        runCatching {
            val prompt = _postCallPrompt.value ?: return@launch
            var cid = prompt.customerId
            if (cid == 0L && !phoneIfMissing.isNullOrBlank()) {
                val phone = phoneIfMissing
                cid = repo.upsert(
                    Customer(
                        name = nameIfMissing?.takeIf { it.isNotBlank() } ?: phone,
                        phone = phone,
                        phoneNormalized = Formatter.normalizePhone(phone)
                    )
                )
            }
            if (cid > 0L) {
                repo.recordFollowUp(
                    customerId = cid,
                    result = result,
                    durationSec = prompt.durationSec,
                    note = note,
                    remindAt = remindAt,
                    fromPostCall = true
                )
                // 登记了下次跟进 → 重排闹钟
                if (remindAt != null) reminderScheduler.scheduleNext()
            }
            _postCallPrompt.value = null
        }.onFailure { it.printStackTrace() }
    }

    fun dial(customer: Customer, direct: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                repo.incDial(customer.id)
                if (direct) dialerHelper.directCall(customer.phone)
                else dialerHelper.openDialer(customer.phone)
            }.onFailure { it.printStackTrace() }
        }
    }
}