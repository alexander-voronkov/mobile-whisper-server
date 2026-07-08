package com.example.whisperserver.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database holding the durable transcription journal. Kept intentionally
 * small (one table); schema export is off since there are no migrations to track
 * yet — a version bump would rebuild the DB destructively (the journal is a
 * convenience history, not source-of-truth data).
 */
@Database(entities = [TranscriptionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transcriptions(): TranscriptionDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "whisper.db",
            )
                .fallbackToDestructiveMigration()
                .build()
    }
}
