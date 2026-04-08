package org.ntust.app.tigerduck.data.local

import androidx.room.*
import org.ntust.app.tigerduck.data.model.Announcement
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AnnouncementDao {
    @Query("SELECT * FROM announcements ORDER BY publishDate DESC")
    abstract fun observeAll(): Flow<List<Announcement>>

    @Query("SELECT * FROM announcements ORDER BY publishDate DESC")
    abstract suspend fun getAll(): List<Announcement>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(announcements: List<Announcement>)

    @Query("DELETE FROM announcements")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceAll(announcements: List<Announcement>) {
        deleteAll()
        insertAll(announcements)
    }
}
