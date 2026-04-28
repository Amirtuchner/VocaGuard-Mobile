package io.vocaguard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyAlertDao {

    @Query("SELECT * FROM family_alerts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<FamilyAlertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: FamilyAlertEntity)

    @Query("UPDATE family_alerts SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE family_alerts SET isRead = 1")
    suspend fun markAllRead()

    @Query("SELECT COUNT(*) FROM family_alerts WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("DELETE FROM family_alerts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM family_alerts")
    suspend fun clearAll()
}
