package com.example.vocaguard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScamNumberDao {

    @Query("SELECT * FROM scam_numbers")
    suspend fun getAll(): List<ScamNumberEntity>

    @Query("SELECT * FROM scam_numbers WHERE phoneNumber = :phoneNumber")
    suspend fun getByNumber(phoneNumber: String): ScamNumberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScamNumberEntity)

    @Query("DELETE FROM scam_numbers WHERE phoneNumber = :phoneNumber")
    suspend fun deleteByNumber(phoneNumber: String)

    @Query("SELECT COUNT(*) FROM scam_numbers")
    suspend fun count(): Int

    @Query("DELETE FROM scam_numbers")
    suspend fun clearAll()
}