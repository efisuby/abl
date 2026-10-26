package com.efisuby.abl.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AblDatabase::class.java)

    @Test
    fun migrate1To2KeepsBooksAndAddsMetadataTime() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO books (id, title, source, totalDurationMs, status, downloadState, addedAt) " +
                    "VALUES ('b1', 'Книга', 'LOCAL', 1000, 'NOT_STARTED', 'DOWNLOADED', 5)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, AblDatabase.MIGRATION_1_2)

        db.query("SELECT title, metadataUpdatedAt FROM books WHERE id = 'b1'").use {
            it.moveToFirst()
            assertEquals("Книга", it.getString(0))
            assertEquals(0L, it.getLong(1))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
