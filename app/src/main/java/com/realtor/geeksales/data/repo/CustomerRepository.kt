package com.realtor.geeksales.data.repo

import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.CustomerDao
import com.realtor.geeksales.data.db.CustomerTagMap
import com.realtor.geeksales.data.db.FollowUp
import com.realtor.geeksales.data.db.FollowUpDao
import com.realtor.geeksales.data.db.FollowResult
import com.realtor.geeksales.data.db.IntentCount
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.db.Tag
import com.realtor.geeksales.data.db.TagDao
import com.realtor.geeksales.util.Formatter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomerRepository @Inject constructor(
    private val customerDao: CustomerDao,
    private val followUpDao: FollowUpDao,
    private val tagDao: TagDao
) {
    fun observeFiltered(
        query: String?,
        level: IntentLevel?,
        onlyOverdue: Boolean,
        onlyQueued: Boolean,
        onlyCalledToday: Boolean = false,
        onlyNotCalled: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): Flow<List<Customer>> = customerDao.observeFiltered(
        query = query?.takeIf { it.isNotBlank() },
        level = level,
        onlyOverdue = if (onlyOverdue) 1 else 0,
        onlyQueued = if (onlyQueued) 1 else 0,
        onlyCalledToday = if (onlyCalledToday) 1 else 0,
        onlyNotCalled = if (onlyNotCalled) 1 else 0,
        now = now,
        todayStart = Formatter.todayStart()
    )

    fun observeById(id: Long): Flow<Customer?> = customerDao.observeById(id)

    suspend fun getFilteredForExport(
        query: String?,
        level: IntentLevel?,
        onlyOverdue: Boolean,
        onlyQueued: Boolean,
        onlyCalledToday: Boolean = false,
        onlyNotCalled: Boolean = false,
        now: Long = System.currentTimeMillis(),
        todayStart: Long = Formatter.todayStart()
    ): List<Customer> = customerDao.getFilteredForExport(
        query = query?.takeIf { it.isNotBlank() },
        level = level,
        onlyOverdue = if (onlyOverdue) 1 else 0,
        onlyQueued = if (onlyQueued) 1 else 0,
        onlyCalledToday = if (onlyCalledToday) 1 else 0,
        onlyNotCalled = if (onlyNotCalled) 1 else 0,
        now = now,
        todayStart = todayStart
    )

    suspend fun getTodayStats(): TodayStats {
        val now = System.currentTimeMillis()
        val todayStart = Formatter.todayStart()
        val todayEnd = todayStart + 86_400_000L
        return TodayStats(
            totalCustomers = customerDao.countAll(),
            calledToday = customerDao.countCalledToday(todayStart),
            notCalledToday = customerDao.countNotCalledToday(todayStart),
            overdue = customerDao.countOverdue(now),
            followUpsToday = followUpDao.countToday(todayStart, todayEnd),
            intentCounts = customerDao.groupByIntent()
        )
    }

    suspend fun upsert(customer: Customer, tags: List<String>? = null): Long {
        val id = customerDao.upsert(
            customer.copy(updatedAt = System.currentTimeMillis())
        )
        if (tags != null) {
            tagDao.unlinkAllForCustomer(id)
            tags.filter { it.isNotBlank() }.distinct().forEach { name ->
                val tagId = tagDao.createIfAbsent(Tag(name = name))
                // createIfAbsent 在标签已存在时（IGNORE 冲突）返回 -1，
                // 此时必须查回已有 id，否则该客户永远关联不上已存在的标签。
                val resolvedId = if (tagId > 0) tagId else tagDao.getByName(name)?.id ?: 0L
                if (resolvedId > 0) {
                    tagDao.link(CustomerTagMap(id, resolvedId))
                }
            }
        }
        return id
    }

    /** 读取客户现有标签名，用于编辑页回显（避免保存时误删原标签） */
    suspend fun tagsOf(customerId: Long): List<String> =
        tagDao.getForCustomer(customerId).map { it.name }

    /** 仅给客户挂标签（不重写客户数据），用于通讯录导入的群组映射等批量场景 */
    suspend fun applyTags(customerId: Long, tags: List<String>) {
        if (customerId <= 0L) return
        val names = tags.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return
        names.forEach { name ->
            val tagId = tagDao.createIfAbsent(Tag(name = name))
            val resolvedId = if (tagId > 0) tagId else tagDao.getByName(name)?.id ?: 0L
            if (resolvedId > 0) {
                tagDao.link(CustomerTagMap(customerId, resolvedId))
            }
        }
    }

    suspend fun deleteById(id: Long) = customerDao.deleteById(id)

    suspend fun countAll(): Int = customerDao.countAll()

    suspend fun deleteAll() = customerDao.deleteAll()

    suspend fun getById(id: Long): Customer? = customerDao.getById(id)

    suspend fun incDial(id: Long, at: Long = System.currentTimeMillis()) =
        customerDao.incDialCount(id, at)

    fun observeQueue(): Flow<List<Customer>> = customerDao.observeQueue()

    suspend fun addToQueue(id: Long) {
        // MAX+1 保证不与残留编号冲突（COUNT+1 在中间移除后会撞号）
        customerDao.setQueue(id, true, customerDao.nextQueueOrder())
    }

    suspend fun addToQueueBatch(ids: List<Long>) {
        if (ids.isEmpty()) return
        // 单事务内逐条赋唯一编号，事务提交后 Room Flow 只发射一次，避免重组风暴
        customerDao.enqueueAllOrdered(ids)
    }

    suspend fun removeFromQueue(id: Long) {
        customerDao.setQueue(id, false, null)
        // 出队后重排，保持编号连续（1..N），与队列页位置编号一致
        customerDao.normalizeQueue()
    }

    fun observeCount() = customerDao.observeCount()

    suspend fun getAll() = customerDao.getAll()

    suspend fun getByPhoneNormalized(p: String) = customerDao.getByPhoneNormalized(p)

    suspend fun clearQueue() = customerDao.clearQueue()

    suspend fun normalizeQueueOnLaunch() = customerDao.normalizeQueue()

    suspend fun upsertAll(list: List<Customer>) = customerDao.upsertAll(list)

    suspend fun groupByIntent() = customerDao.groupByIntent()

    // ---- FollowUps ----
    suspend fun recordFollowUp(
        customerId: Long,
        result: FollowResult,
        durationSec: Int,
        note: String?,
        remindAt: Long?,
        fromPostCall: Boolean = false
    ) {
        followUpDao.insert(
            FollowUp(
                customerId = customerId,
                result = result,
                durationSec = durationSec,
                note = note,
                remindAt = remindAt,
                fromPostCall = fromPostCall
            )
        )
        // 同步 Customer 的下次跟进时间
        val c = customerDao.getById(customerId) ?: return
        val level = when (result) {
            FollowResult.APPOINTMENT, FollowResult.CONNECTED -> IntentLevel.A
            FollowResult.PENDING -> IntentLevel.B
            FollowResult.NOT_INTERESTED -> IntentLevel.C
            FollowResult.WRONG_NUMBER, FollowResult.SHUTDOWN -> IntentLevel.D
            FollowResult.NOT_REACHED -> IntentLevel.U
        }
        customerDao.upsert(
            c.copy(
                nextFollowAt = remindAt ?: c.nextFollowAt,
                intentLevel = if (level == IntentLevel.U) c.intentLevel else level,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun observeFollowUpsOf(customerId: Long): Flow<List<FollowUp>> =
        followUpDao.observeByCustomer(customerId)

    fun observeRecentFollowUps(limit: Int = 50) = followUpDao.observeRecent(limit)

    suspend fun countFollowUpsInRange(start: Long, end: Long) =
        followUpDao.countToday(start, end)

    suspend fun followUpsInRange(start: Long, end: Long) =
        followUpDao.getByRange(start, end)
}

data class TodayStats(
    val totalCustomers: Int,
    val calledToday: Int,
    val notCalledToday: Int,
    val overdue: Int,
    val followUpsToday: Int,
    val intentCounts: List<IntentCount>
)