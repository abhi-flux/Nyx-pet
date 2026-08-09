package com.nyx.pet.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A taught skill, stored permanently on-device.
 * `stepsJson` holds the full ordered list of SkillStep, serialized with Gson,
 * so we don't need a second table just for steps.
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val stepsJson: String,
    val createdAt: Long = System.currentTimeMillis()
)
