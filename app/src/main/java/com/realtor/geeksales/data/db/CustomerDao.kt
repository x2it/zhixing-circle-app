package com.realtor.geeksales.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {

    /**
     * 使用 @Upsert（INSERT ... ON CONFLICT DO UPDATE）而非 REPLACE：
     * REPLACE 在 SQLite 里是"删旧行再插新行"，会触发 follow_ups 外键 CASCADE，
     * 导致编辑客户时该客户的全部跟进记录被清空（严重数据丢失 bug）。
     */
    @Upsert
    suspend fun upsert(customer: Customer): Long

    @Upsert
    suspend fun upsertAll(customers: List<Customer>): List<Long>

    @Update
    suspend fun update(customer: Customer)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM customers WHERE id = :id")
    fun observeById(id: Long): Flow<Customer?>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getById(id: Long): Customer?

    @Query("SELECT * FROM customers WHERE phoneNormalized = :p LIMIT 1")
    suspend fun getByPhoneNormalized(p: String): Customer?

    @Query("""
        SELECT * FROM customers 
        WHERE (:query IS NULL OR (name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR targetProject LIKE '%' || :query || '%' OR areaPref LIKE '%' || :query || '%'))
          AND (:level IS NULL OR intentLevel = :level)
          AND (:tagName IS NULL OR EXISTS (
                SELECT 1 FROM customer_tag_map ct JOIN tags t ON t.id = ct.tagId
                WHERE ct.customerId = customers.id AND t.name = :tagName))
          AND (:onlyOverdue = 0 OR (nextFollowAt IS NOT NULL AND nextFollowAt <= :now))
          AND (:onlyQueued = 0 OR queued = 1)
          AND (:onlyCalledToday = 0 OR (lastDialAt IS NOT NULL AND lastDialAt >= :todayStart))
          AND (:onlyNotCalled = 0 OR (lastDialAt IS NULL OR lastDialAt < :todayStart))
        ORDER BY 
          CASE WHEN :onlyQueued = 1 THEN queueOrder END ASC,
          CASE WHEN nextFollowAt IS NULL THEN 1 ELSE 0 END ASC,
          nextFollowAt ASC,
          updatedAt DESC
    """)
    fun observeFiltered(
        query: String? = null,
        level: IntentLevel? = null,
        tagName: String? = null,
        onlyOverdue: Int = 0,
        onlyQueued: Int = 0,
        onlyCalledToday: Int = 0,
        onlyNotCalled: Int = 0,
        now: Long = System.currentTimeMillis(),
        todayStart: Long = 0
    ): Flow<List<Customer>>

    /** 今日需跟进客户（nextFollowAt 落在 [todayStart, tomorrowStart)），工作台「今日跟进」用 */
    @Query("""
        SELECT * FROM customers 
        WHERE nextFollowAt IS NOT NULL AND nextFollowAt >= :todayStart AND nextFollowAt < :tomorrowStart
        ORDER BY nextFollowAt ASC
    """)
    fun observeTodayFollowups(todayStart: Long, tomorrowStart: Long): Flow<List<Customer>>

    /** 过期未跟进客户（nextFollowAt < now），工作台「已过期」用 */
    @Query("""
        SELECT * FROM customers 
        WHERE nextFollowAt IS NOT NULL AND nextFollowAt <= :now
        ORDER BY nextFollowAt ASC
    """)
    fun observeOverdue(now: Long): Flow<List<Customer>>

    @Query("""
        SELECT * FROM customers 
        WHERE (:query IS NULL OR (name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR targetProject LIKE '%' || :query || '%' OR areaPref LIKE '%' || :query || '%'))
          AND (:level IS NULL OR intentLevel = :level)
          AND (:tagName IS NULL OR EXISTS (
                SELECT 1 FROM customer_tag_map ct JOIN tags t ON t.id = ct.tagId
                WHERE ct.customerId = customers.id AND t.name = :tagName))
          AND (:onlyOverdue = 0 OR (nextFollowAt IS NOT NULL AND nextFollowAt <= :now))
          AND (:onlyQueued = 0 OR queued = 1)
          AND (:onlyCalledToday = 0 OR (lastDialAt IS NOT NULL AND lastDialAt >= :todayStart))
          AND (:onlyNotCalled = 0 OR (lastDialAt IS NULL OR lastDialAt < :todayStart))
        ORDER BY updatedAt DESC
    """)
    suspend fun getFilteredForExport(
        query: String? = null,
        level: IntentLevel? = null,
        tagName: String? = null,
        onlyOverdue: Int = 0,
        onlyQueued: Int = 0,
        onlyCalledToday: Int = 0,
        onlyNotCalled: Int = 0,
        now: Long = System.currentTimeMillis(),
        todayStart: Long = 0
    ): List<Customer>

    @Query("UPDATE customers SET queued = :queued, queueOrder = :order WHERE id = :id")
    suspend fun setQueue(id: Long, queued: Boolean, order: Int?)

    @Query("UPDATE customers SET queued = 1, queueOrder = :startOrder WHERE id IN (:ids)")
    suspend fun setQueueBatch(ids: List<Long>, startOrder: Int)

    /**
     * 批量入队（单事务）：逐条赋唯一递增编号。
     * 单事务保证 Room Flow 只在提交后重查询一次，避免重组风暴导致假死；
     * 且每条客户获得唯一的 queueOrder，编号不再重复。
     */
    @Transaction
    suspend fun enqueueAllOrdered(ids: List<Long>) {
        if (ids.isEmpty()) return
        var order = nextQueueOrder()
        ids.forEach { id ->
            setQueue(id, true, order)
            order++
        }
    }

    @Query("UPDATE customers SET queued = 0, queueOrder = NULL WHERE queued = 1")
    suspend fun clearQueue()

    @Query("UPDATE customers SET dialCount = dialCount + 1, lastDialAt = :at WHERE id = :id")
    suspend fun incDialCount(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM customers WHERE queued = 1")
    suspend fun queueCount(): Int

    @Query("""
        SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM customers WHERE queued = 1
    """)
    suspend fun nextQueueOrder(): Int

    @Query("SELECT * FROM customers WHERE queued = 1 ORDER BY queueOrder ASC, id ASC")
    fun observeQueue(): Flow<List<Customer>>

    @Query("SELECT id FROM customers WHERE queued = 1 ORDER BY queueOrder ASC, id ASC")
    suspend fun queuedIds(): List<Long>

    /**
     * 队列重排：出队/移除后把剩余成员的 queueOrder 归一化为 1..N。
     * 保证队列页的位置编号与名单页 Q# 标签完全一致，且永不跳号、永不冲突。
     */
    @Transaction
    suspend fun normalizeQueue() {
        val ids = queuedIds()
        ids.forEachIndexed { i, id -> setQueue(id, true, i + 1) }
    }

    @Query("SELECT COUNT(*) FROM customers")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun countAll(): Int

    @Query("DELETE FROM customers")
    suspend fun deleteAll()

    @Query("SELECT * FROM customers")
    suspend fun getAll(): List<Customer>

    @Query("SELECT intentLevel as level, COUNT(*) as cnt FROM customers GROUP BY intentLevel")
    suspend fun groupByIntent(): List<IntentCount>

    @Query("SELECT COUNT(*) FROM customers WHERE lastDialAt IS NOT NULL AND lastDialAt >= :todayStart")
    suspend fun countCalledToday(todayStart: Long): Int

    @Query("SELECT COUNT(*) FROM customers WHERE nextFollowAt IS NOT NULL AND nextFollowAt <= :now")
    suspend fun countOverdue(now: Long): Int

    @Query("SELECT COUNT(*) FROM customers WHERE lastDialAt IS NULL OR lastDialAt < :todayStart")
    suspend fun countNotCalledToday(todayStart: Long): Int

    @Query("SELECT COUNT(*) FROM follow_ups WHERE createdAt >= :dayStart AND createdAt < :dayEnd")
    suspend fun countFollowUpsToday(dayStart: Long, dayEnd: Long): Int
}

data class IntentCount(
    val level: String,
    val cnt: Int
)