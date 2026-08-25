package com.piercingxx.xxlauncher.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val sortOrder: Int
)

// Member table for folder contents
@Entity(tableName = "folder_members", primaryKeys = ["folderId", "appId"])
data class FolderMember(
    val folderId: Int,
    val appId: String,
    /** Manual position inside the folder; normalized to 0..n-1 on read. */
    val sortOrder: Int = 0
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY sortOrder ASC")
    fun getAllFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    fun getFolder(id: Int): Flow<Folder?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Delete
    suspend fun delete(folder: Folder)

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int
    
    @Query("SELECT COUNT(*) FROM folders WHERE name = :name COLLATE NOCASE")
    suspend fun countByName(name: String): Int

    // Folder member operations
    @Query("SELECT * FROM folder_members WHERE folderId = :folderId ORDER BY sortOrder ASC, appId ASC")
    suspend fun getFolderMembers(folderId: Int): List<FolderMember>

    @Query("SELECT * FROM folder_members")
    suspend fun getAllMembers(): List<FolderMember>

    @Query("SELECT MAX(sortOrder) FROM folder_members WHERE folderId = :folderId")
    suspend fun maxMemberSortOrder(folderId: Int): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: FolderMember)

    @Update
    suspend fun updateMembers(members: List<FolderMember>)

    @Delete
    suspend fun deleteMember(member: FolderMember)

    @Query("DELETE FROM folder_members WHERE folderId = :folderId AND appId = :appId")
    suspend fun removeMember(folderId: Int, appId: String)
}

@Database(
    entities = [Folder::class, FolderMember::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Manual folder ordering. Existing rows all land on 0; FolderManager
         * renumbers them alphabetically on the next read, so folders keep the
         * order they had before the upgrade.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE folder_members ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Drops the never-used home_slots table. Home slots have always lived
         * in SharedPreferences; the table was created but never read or
         * written, so there is nothing to carry forward.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS home_slots")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "launcher.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
