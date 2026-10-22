package com.ayaks.winterforest.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "base_progress")
data class BaseProgressEntity(
    @PrimaryKey val slotId: Int = SINGLETON_SLOT_ID,
    val payloadJson: String,
    val crc32: Long,
    val updatedAtEpochMs: Long
) {
    companion object {
        const val SINGLETON_SLOT_ID = 1
    }
}

@Entity(tableName = "active_run")
data class ActiveRunEntity(
    @PrimaryKey val slotId: Int = SINGLETON_SLOT_ID,
    val payloadJson: String,
    val crc32: Long,
    val updatedAtEpochMs: Long
) {
    companion object {
        const val SINGLETON_SLOT_ID = 1
    }
}

@Dao
interface GameProgressDao {
    @Query("SELECT * FROM base_progress WHERE slotId = 1 LIMIT 1")
    suspend fun getBaseProgress(): BaseProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBaseProgress(entity: BaseProgressEntity)

    @Query("DELETE FROM base_progress")
    suspend fun clearBaseProgress()

    @Query("SELECT * FROM active_run WHERE slotId = 1 LIMIT 1")
    suspend fun getActiveRun(): ActiveRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveRun(entity: ActiveRunEntity)

    @Query("DELETE FROM active_run")
    suspend fun clearActiveRun()
}

@Database(
    entities = [BaseProgressEntity::class, ActiveRunEntity::class],
    version = 1,
    exportSchema = false
)
abstract class WinterForestDatabase : RoomDatabase() {
    abstract fun gameProgressDao(): GameProgressDao

    companion object {
        const val DATABASE_NAME = "winter_forest.db"
    }
}
