package org.ntust.app.tigerduck.data.local

import androidx.room.*
import org.ntust.app.tigerduck.data.model.Assignment
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AssignmentDao {
    @Query("SELECT * FROM assignments ORDER BY dueDate ASC")
    abstract fun observeAll(): Flow<List<Assignment>>

    @Query("SELECT * FROM assignments ORDER BY dueDate ASC")
    abstract suspend fun getAll(): List<Assignment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(assignments: List<Assignment>)

    @Query("UPDATE assignments SET isCompleted = :completed WHERE assignmentId = :id")
    abstract suspend fun setCompleted(id: String, completed: Boolean)

    @Query("DELETE FROM assignments")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceAll(assignments: List<Assignment>) {
        deleteAll()
        insertAll(assignments)
    }
}
