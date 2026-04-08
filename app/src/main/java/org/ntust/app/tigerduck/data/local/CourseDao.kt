package org.ntust.app.tigerduck.data.local

import androidx.room.*
import org.ntust.app.tigerduck.data.model.Course
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CourseDao {
    @Query("SELECT * FROM courses")
    abstract fun observeAll(): Flow<List<Course>>

    @Query("SELECT * FROM courses")
    abstract suspend fun getAll(): List<Course>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(courses: List<Course>)

    @Query("DELETE FROM courses")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceAll(courses: List<Course>) {
        deleteAll()
        insertAll(courses)
    }
}
