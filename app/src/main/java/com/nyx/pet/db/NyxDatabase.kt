package com.nyx.pet.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SkillEntity::class], version = 1, exportSchema = false)
abstract class NyxDatabase : RoomDatabase() {

    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile private var INSTANCE: NyxDatabase? = null

        fun get(context: Context): NyxDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NyxDatabase::class.java,
                    "nyx.db"
                ).build().also { INSTANCE = it }
            }
    }
}
