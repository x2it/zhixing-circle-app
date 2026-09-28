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
    private val dialerHelper: DialerHelper,
    private val schemaStore: com.realtor.geeksales.data.schema.SchemaStore
) : ViewModel() {

    /** 当前模板元数据（分层/标签跟随线上模板，驱动智能建队条件） */
    fun templateMeta(): com.realtor.geeksales.data.schema.TemplateMeta = schemaStore.meta()

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

    /** 智能建队：按分层/标签/跟进到期 批量入队（只加入未入队者），完成后回调加入数量 */
    fun addSmart(
        level: com.realtor.geeksales.data.db.IntentLevel? = null,
        tag: String? = null,
        onlyOverdue: Boolean = false,
        dueWithinDays: Long = 0,
        onDone: (Int) -> Unit = {}
    ) = viewModelScope.launch {
        val added = runCatching {
            val now = System.currentTimeMillis()
            val list = repo.getFilteredForExport(
                query = null, level = level, tagName = tag,
                onlyOverdue = onlyOverdue, onlyQueued = false, now = now
            )
            val ids = list.filter { c ->
                !c.queued && (dueWithinDays <= 0 || c.nextFollowAt == null || c.nextFollowAt <= now + dueWithinDays * 86400000L)
            }.map { it.id }
            if (ids.isNotEmpty()) repo.addToQueueBatch(ids)
            ids.size
        }.getOrElse { 0 }
        onDone(added)
    }
}