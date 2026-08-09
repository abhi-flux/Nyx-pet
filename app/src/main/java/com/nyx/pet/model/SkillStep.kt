package com.nyx.pet.model

/**
 * One single action inside a Skill.
 * Only the fields relevant to `type` are used:
 *   TAP      -> x, y
 *   SWIPE    -> x, y (start point), x2, y2 (end point), durationMs
 *   TYPE     -> text
 *   WAIT     -> delayMs
 *   OPEN_APP -> packageName
 */
data class SkillStep(
    val type: StepType,
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Long = 300,
    val text: String? = null,
    val delayMs: Long = 0,
    val packageName: String? = null
)
