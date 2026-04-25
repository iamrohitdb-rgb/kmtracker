package com.example.kmtrackerpro

// ─────────────────────────────────────────────────────────────
//  Database.kt  –  Room setup (Entity + DAO + Database)
//
//  Room is Android's official SQLite wrapper.
//  It turns plain Kotlin data classes into database tables and
//  generates all the boilerplate SQL for us.
// ─────────────────────────────────────────────────────────────

import androidx.lifecycle.LiveData
import androidx.room.*

// ── 1. Entity ─────────────────────────────────────────────────
// @Entity maps this class to a database table called "runs".
// Each property becomes a column.
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val date: String,          // e.g. "25 Apr 2026"
    val distanceKm: Double,    // total distance in kilometres
    val durationSeconds: Long, // total time in seconds
    val avgSpeedKmh: Double    // average speed km/h
)

// ── 2. DAO (Data Access Object) ───────────────────────────────
// All database queries live here.  Room generates the
// implementation at compile time from these annotations.
@Dao
interface RunDao {

    // Insert a new run after it finishes
    @Insert
    suspend fun insertRun(run: RunEntity)

    // Get all runs, newest first – returns LiveData so the UI
    // updates automatically whenever a new run is saved
    @Query("SELECT * FROM runs ORDER BY id DESC")
    fun getAllRuns(): LiveData<List<RunEntity>>

    // Delete a specific run by its id
    @Query("DELETE FROM runs WHERE id = :runId")
    suspend fun deleteRun(runId: Int)
}

// ── 3. Database ───────────────────────────────────────────────
// @Database lists every entity (table) and the schema version.
// If you add a new column, increment the version number.
@Database(entities = [RunEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun runDao(): RunDao

    companion object {
        // Volatile ensures changes are immediately visible to other threads
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            // Only one thread can create the database at a time
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "km_tracker_database" // filename on disk
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
