package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entities.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters ORDER BY orderIndex ASC, id ASC")
    fun getAllChapters(): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters ORDER BY orderIndex ASC, id ASC")
    suspend fun getAllChaptersSync(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE subject = :subject ORDER BY orderIndex ASC, id ASC")
    fun getChaptersBySubject(subject: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getChapterById(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE isWeak = 1 OR status = 'WEAK'")
    fun getWeakChapters(): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Update
    suspend fun updateChapter(chapter: ChapterEntity)

    @Query("DELETE FROM chapters WHERE id = :id")
    suspend fun deleteChapter(id: Long)

    @Query("DELETE FROM chapters")
    suspend fun clearAllChapters()

    @Query("SELECT COUNT(*) FROM chapters")
    suspend fun getChapterCount(): Int
}
