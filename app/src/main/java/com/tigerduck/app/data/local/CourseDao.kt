package com.tigerduck.app.data.local

import androidx.room.*
import com.tigerduck.app.data.model.Course
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses")
    fun observeAll(): Flow<List<Course>>

    @Query("SELECT * FROM courses")
    suspend fun getAll(): List<Course>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<Course>)

    @Query("DELETE FROM courses")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(courses: List<Course>) {
        deleteAll()
        insertAll(courses)
    }
}
