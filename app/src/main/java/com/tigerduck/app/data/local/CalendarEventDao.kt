package com.tigerduck.app.data.local

import androidx.room.*
import com.tigerduck.app.data.model.CalendarEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM calendar_events ORDER BY date ASC")
    fun observeAll(): Flow<List<CalendarEvent>>

    @Query("SELECT * FROM calendar_events ORDER BY date ASC")
    suspend fun getAll(): List<CalendarEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalendarEvent>)

    @Query("DELETE FROM calendar_events WHERE sourceRaw = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM calendar_events")
    suspend fun deleteAll()
}
