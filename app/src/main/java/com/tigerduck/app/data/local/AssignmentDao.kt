package com.tigerduck.app.data.local

import androidx.room.*
import com.tigerduck.app.data.model.Assignment
import kotlinx.coroutines.flow.Flow

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignments ORDER BY dueDate ASC")
    fun observeAll(): Flow<List<Assignment>>

    @Query("SELECT * FROM assignments ORDER BY dueDate ASC")
    suspend fun getAll(): List<Assignment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assignments: List<Assignment>)

    @Query("UPDATE assignments SET isCompleted = :completed WHERE assignmentId = :id")
    suspend fun setCompleted(id: String, completed: Boolean)

    @Query("DELETE FROM assignments")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(assignments: List<Assignment>) {
        deleteAll()
        insertAll(assignments)
    }
}
