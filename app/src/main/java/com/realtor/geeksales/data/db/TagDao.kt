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
}
