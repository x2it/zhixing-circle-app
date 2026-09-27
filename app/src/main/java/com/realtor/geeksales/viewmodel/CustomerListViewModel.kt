package com.realtor.geeksales.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.FollowResult
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.telephony.DialerHelper
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomerFilter(
    val query: String = "",
    val level: IntentLevel? = null,
    val onlyOverdue: Boolean = false,
    val onlyQueued: Boolean = false,
    val onlyCalledToday: Boolean = false,
    val onlyNotCalled: Boolean = false
)

@HiltViewModel
class CustomerListViewModel @Inject constructor(
    private val repo: CustomerRepository,
    private val dialerHelper: DialerHelper
) : ViewModel() {

    private val filter = MutableStateFlow(CustomerFilter())
    private val nowTick = MutableStateFlow(System.currentTimeMillis())

    val customers = combine(filter, nowTick) { f, n -> f to n }
        .let { combined ->
            @OptIn(ExperimentalCoroutinesApi::class)
            combined.flatMapLatest { (f, n) ->
                repo.observeFiltered(
                    query = f.query,
                    level = f.level,
                    onlyOverdue = f.onlyOverdue,
                    onlyQueued = f.onlyQueued,
                    onlyCalledToday = f.onlyCalledToday,
                    onlyNotCalled = f.onlyNotCalled,
                    now = n
                )
            }
        }
        .conflate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount = repo.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setFilter(f: CustomerFilter) { filter.value = f }
    fun tickNow() { nowTick.value = System.currentTimeMillis() }

    suspend fun getFilteredForExport(): List<Customer> = repo.getFilteredForExport(
        query = filter.value.query,
        level = filter.value.level,
        onlyOverdue = filter.value.onlyOverdue,
        onlyQueued = filter.value.onlyQueued,
        onlyCalledToday = filter.value.onlyCalledToday,
        onlyNotCalled = filter.value.onlyNotCalled
    )

    fun toggleQueue(c: Customer) = viewModelScope.launch {
        if (c.queued) repo.removeFromQueue(c.id) else repo.addToQueue(c.id)
    }

    fun dial(c: Customer, direct: Boolean = false) = viewModelScope.launch {
        repo.incDial(c.id)
        if (direct) dialerHelper.directCall(c.phone) else dialerHelper.openDialer(c.phone)
    }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteById(id) }

    fun addAllFilteredToQueue() = viewModelScope.launch {
        val list = customers.value.filter { !it.queued }
        if (list.isEmpty()) return@launch
        val ids = list.map { it.id }
        // 一次性批量入队，单事务更新
        repo.addToQueueBatch(ids)
    }
}