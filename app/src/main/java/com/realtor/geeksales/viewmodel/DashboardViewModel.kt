package com.realtor.geeksales.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.IntentCount
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.data.repo.TodayStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: CustomerRepository
) : ViewModel() {

    val totalCustomers: StateFlow<Int> = repo.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val todayStats: StateFlow<TodayStats> = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }.flatMapLatest {
        flow { emit(repo.getTodayStats()) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TodayStats(0, 0, 0, 0, 0, emptyList())
    )

    val calledToday: StateFlow<Int> =
        combine(todayStats, totalCustomers) { ts, _ -> ts.calledToday }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val notCalledToday: StateFlow<Int> =
        combine(todayStats, totalCustomers) { ts, _ -> ts.notCalledToday }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val overdue: StateFlow<Int> =
        combine(todayStats, totalCustomers) { ts, _ -> ts.overdue }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val followUpsToday: StateFlow<Int> =
        combine(todayStats, totalCustomers) { ts, _ -> ts.followUpsToday }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val intentCounts: StateFlow<List<IntentCount>> =
        combine(todayStats, totalCustomers) { ts, _ -> ts.intentCounts }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 过期未跟进客户（红，最优先处理） */
    val overdueCustomers: StateFlow<List<com.realtor.geeksales.data.db.Customer>> =
        repo.observeOverdue()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 今日需跟进客户（黄） */
    val todayCustomers: StateFlow<List<com.realtor.geeksales.data.db.Customer>> =
        repo.observeTodayFollowups()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val REFRESH_MS = 30_000L
    }
}