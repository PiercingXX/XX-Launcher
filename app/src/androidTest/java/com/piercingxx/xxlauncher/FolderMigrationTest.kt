package com.piercingxx.xxlauncher

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piercingxx.xxlauncher.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs MIGRATION_1_2 and MIGRATION_2_3 against a hand-built v1 database.
 * [FolderOrderTest] opens a live v3 file and never exercises these paths.
 */
@RunWith(AndroidJUnit4::class)
class FolderMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1to3AddsMemberSortOrderAndDropsHomeSlots() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS folders (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "sortOrder INTEGER NOT NULL)"
            )
            execSQL(
                "CREATE TABLE IF NOT EXISTS folder_members (" +
                    "folderId INTEGER NOT NULL, " +
                    "appId TEXT NOT NULL, " +
                    "PRIMARY KEY(folderId, appId))"
            )
            execSQL(
                "CREATE TABLE IF NOT EXISTS home_slots (" +
                    "slotIndex INTEGER NOT NULL PRIMARY KEY, " +
                    "label TEXT NOT NULL)"
            )
            execSQL("INSERT INTO folders (name, sortOrder) VALUES ('Tools', 0)")
            execSQL(
                "INSERT INTO folder_members (folderId, appId) " +
                    "VALUES (1, 'com.example.app|personal')"
            )
            execSQL("INSERT INTO home_slots (slotIndex, label) VALUES (1, 'Notes')")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        )

        db.query("SELECT sortOrder, appId FROM folder_members").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals("com.example.app|personal", cursor.getString(1))
        }
        db.query("SELECT COUNT(*) FROM folders").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='home_slots'"
        ).use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
