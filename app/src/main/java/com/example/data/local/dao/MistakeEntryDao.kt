package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entities.MistakeEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MistakeEntryDao {
    @Query("SELECT * FROM mistake_entries ORDER BY timestamp DESC")
    fun getAllMistakes(): Flow<List<MistakeEntryEntity>>

    @Query("SELECT * FROM mistake_entries WHERE isResolved = 0 ORDER BY timestamp DESC")
    fun getUnresolvedMistakes(): Flow<List<MistakeEntryEntity>>

    @Query("SELECT * FROM mistake_entries WHERE subject = :subject ORDER BY timestamp DESC")
    fun getMistakesBySubject(subject: String): Flow<List<MistakeEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMistake(mistake: MistakeEntryEntity): Long

    @Update
    suspend fun updateMistake(mistake: MistakeEntryEntity)

    @Query("DELETE FROM mistake_entries WHERE id = :id")
    suspend fun deleteMistake(id: Long)
}
