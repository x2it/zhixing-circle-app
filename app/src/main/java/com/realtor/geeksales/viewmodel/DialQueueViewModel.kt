package com.realtor.geeksales.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.FollowResult
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.telephony.DialerHelper
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DialQueueViewModel @Inject constructor(
    private val repo: CustomerRepository,
    private val dialerHelper: DialerHelper
) : ViewModel() {

    val queue: StateFlow<List<Customer>> = repo.observeQueue()
        .conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dialFirst(direct: Boolean = false) = viewModelScope.launch {
        runCatching {
            val c = queue.value.firstOrNull() ?: return@launch
            repo.incDial(c.id)
            if (direct) dialerHelper.directCall(c.phone) else dialerHelper.openDialer(c.phone)
        }.onFailure { it.printStackTrace() }
    }

    fun dialAt(index: Int, direct: Boolean = false) = viewModelScope.launch {
        runCatching {
            val c = queue.value.getOrNull(index) ?: return@launch
            repo.incDial(c.id)
            if (direct) dialerHelper.directCall(c.phone) else dialerHelper.openDialer(c.phone)
        }.onFailure { it.printStackTrace() }
    }

    fun remove(id: Long) = viewModelScope.launch {
        runCatching { repo.removeFromQueue(id) }.onFailure { it.printStackTrace() }
    }

    fun clear() = viewModelScope.launch {
        runCatching {
            repo.clearQueue()
        }.onFailure { it.printStackTrace() }
    }
}