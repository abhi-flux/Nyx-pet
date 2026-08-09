package com.nyx.pet.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SkillDao {

    @Insert
    suspend fun insert(skill: SkillEntity): Long

    @Query("SELECT * FROM skills ORDER BY createdAt DESC")
    suspend fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SkillEntity?

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: Long)
}
