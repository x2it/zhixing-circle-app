package com.realtor.geeksales.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun createIfAbsent(tag: Tag): Long

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT t.* FROM tags t INNER JOIN customer_tag_map m ON t.id = m.tagId WHERE m.customerId = :customerId ORDER BY t.name ASC")
    suspend fun getForCustomer(customerId: Long): List<Tag>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Tag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(map: CustomerTagMap)

    @Query("DELETE FROM customer_tag_map WHERE customerId = :customerId")
    suspend fun unlinkAllForCustomer(customerId: Long)

    @Query("DELETE FROM customer_tag_map WHERE customerId = :customerId AND tagId = :tagId")
    suspend fun deleteMap(customerId: Long, tagId: Long)

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAll(): List<Tag>

    @Query("SELECT * FROM customer_tag_map")
    suspend fun getAllMappings(): List<CustomerTagMap>

    @Query("DELETE FROM customer_tag_map")
    suspend fun clearMappings()

    @Query("DELETE FROM tags")
    suspend fun clearAll()
}
