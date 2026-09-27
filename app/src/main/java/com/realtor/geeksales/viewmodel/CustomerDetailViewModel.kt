package com.realtor.geeksales.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.FollowResult
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.telephony.DialerHelper
import com.realtor.geeksales.telephony.ReminderScheduler
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    private val repo: CustomerRepository,
    private val dialerHelper: DialerHelper,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private val idFlow = MutableStateFlow(0L)

    fun setCustomerId(id: Long) {
        if (id != idFlow.value) idFlow.value = id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val customer = idFlow.flatMapLatest { id ->
        if (id == 0L) flowOf(null) else repo.observeById(id)
    }.conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val followUps = idFlow.flatMapLatest { id ->
        if (id == 0L) flowOf(emptyList()) else repo.observeFollowUpsOf(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dial(direct: Boolean = false) = viewModelScope.launch {
        runCatching {
            val c = customer.value ?: return@launch
            repo.incDial(c.id)
            if (direct) dialerHelper.directCall(c.phone) else dialerHelper.openDialer(c.phone)
        }.onFailure { it.printStackTrace() }
    }

    fun recordFollowUp(result: FollowResult, note: String?, remindAt: Long?, durationSec: Int = 0) =
        viewModelScope.launch {
            runCatching {
                val id = customer.value?.id ?: return@launch
                repo.recordFollowUp(id, result, durationSec, note, remindAt)
                // 登记了下次跟进 → 重排闹钟
                if (remindAt != null) reminderScheduler.scheduleNext()
            }.onFailure { it.printStackTrace() }
        }

    fun toggleQueue() = viewModelScope.launch {
        runCatching {
            val c = customer.value ?: return@launch
            if (c.queued) repo.removeFromQueue(c.id) else repo.addToQueue(c.id)
        }.onFailure { it.printStackTrace() }
    }
}