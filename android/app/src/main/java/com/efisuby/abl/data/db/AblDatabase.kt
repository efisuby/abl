package com.efisuby.abl.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.efisuby.abl.data.db.dao.BookDao
import com.efisuby.abl.data.db.dao.BookmarkDao
import com.efisuby.abl.data.db.dao.ChapterDao
import com.efisuby.abl.data.db.dao.ProgressDao
import com.efisuby.abl.data.db.entity.BookEntity
import com.efisuby.abl.data.db.entity.BookmarkEntity
import com.efisuby.abl.data.db.entity.ChapterEntity
import com.efisuby.abl.data.db.entity.ProgressEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AblDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun progressDao(): ProgressDao

    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val NAME = "abl.db"

        fun create(context: Context): AblDatabase =
            Room.databaseBuilder(context, AblDatabase::class.java, NAME).build()
    }
}
