package com.example.whisperserver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranscriptionDao {

    /** Newest-first, at most [limit] rows. */
    @Query("SELECT * FROM transcriptions ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TranscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranscriptionEntity)

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun delete(id: Long)

    /** Audio file names of the rows that fall outside the newest [keep] (about to be pruned). */
    @Query(
        "SELECT audioFileName FROM transcriptions " +
            "WHERE audioFileName IS NOT NULL AND id NOT IN " +
            "(SELECT id FROM transcriptions ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun fileNamesBeyond(keep: Int): List<String?>

    /** Delete every row except the newest [keep]. */
    @Query("DELETE FROM transcriptions WHERE id NOT IN (SELECT id FROM transcriptions ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
