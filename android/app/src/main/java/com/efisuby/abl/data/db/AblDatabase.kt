package com.efisuby.abl.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
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
            Room.databaseBuilder(context, AblDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Adds the edit time that metadata synchronization compares (task 8.4). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN metadataUpdatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
