package org.ntust.app.tigerduck.data.local

import androidx.room.*
import org.ntust.app.tigerduck.data.model.Announcement
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnouncementDao {
    @Query("SELECT * FROM announcements ORDER BY publishDate DESC")
    fun observeAll(): Flow<List<Announcement>>

    @Query("SELECT * FROM announcements ORDER BY publishDate DESC")
    suspend fun getAll(): List<Announcement>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(announcements: List<Announcement>)

    @Query("DELETE FROM announcements")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(announcements: List<Announcement>) {
        deleteAll()
        insertAll(announcements)
    }
}
