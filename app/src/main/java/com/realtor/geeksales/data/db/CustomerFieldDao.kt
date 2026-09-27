package com.realtor.geeksales.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerFieldDao {

    @Query("SELECT * FROM customer_fields WHERE customerId = :customerId")
    fun observeFieldsOf(customerId: Long): Flow<List<CustomerField>>

    @Query("SELECT * FROM customer_fields WHERE customerId = :customerId")
    suspend fun fieldsOf(customerId: Long): List<CustomerField>

    @Query("SELECT fieldValue FROM customer_fields WHERE customerId = :customerId AND fieldKey = :key")
    suspend fun valueOf(customerId: Long, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(field: CustomerField)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(fields: List<CustomerField>)

    @Query("DELETE FROM customer_fields WHERE customerId = :customerId")
    suspend fun clearForCustomer(customerId: Long)

    @Query("DELETE FROM customer_fields WHERE customerId = :customerId AND fieldKey = :key")
    suspend fun delete(customerId: Long, key: String)

    @Query("DELETE FROM customer_fields")
    suspend fun clearAll()

    @Query("SELECT * FROM customer_fields")
    suspend fun getAll(): List<CustomerField>

    @Query("SELECT DISTINCT fieldKey FROM customer_fields")
    suspend fun allKeys(): List<String>
}
