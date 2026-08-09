package com.nyx.pet.model

/**
 * One single action inside a Skill.
 * Only the fields relevant to `type` are used:
 *   TAP      -> x, y
 *   TYPE     -> text
 *   WAIT     -> delayMs
 *   OPEN_APP -> packageName
 */
data class SkillStep(
    val type: StepType,
    val x: Float? = null,
    val y: Float? = null,
    val text: String? = null,
    val delayMs: Long = 0,
    val packageName: String? = null
)
