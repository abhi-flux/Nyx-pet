package com.nyx.pet.model

/**
 * Nyx's visual state. Phase 6: drives color + built-in animation on the pet
 * overlay — no external image/sprite files needed.
 */
enum class PetMood {
    IDLE,       // gentle breathing + occasional blink
    RECORDING,  // fast red pulse — something is being taught right now
    RUNNING,    // spinning — a skill is actively replaying
    SUCCESS,    // quick green bounce — a skill just finished
    ERROR       // red shake — something went wrong
}
