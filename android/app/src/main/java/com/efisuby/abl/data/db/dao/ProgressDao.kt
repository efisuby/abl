package com.efisuby.abl.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.efisuby.abl.data.db.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observeByBook(bookId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun getByBook(bookId: String): ProgressEntity?

    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}
