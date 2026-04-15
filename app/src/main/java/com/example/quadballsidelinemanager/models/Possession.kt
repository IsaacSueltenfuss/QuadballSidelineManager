package com.example.quadballsidelinemanager.models

data class Possession (
    /**
     * Creates a unique ID for this possession. Team 1 acronym + "vs." + Team 2 acronym +
     * unique integer identifying this possession.
     */
    val id: String,

    // To be assigned later but for film analysis post game
    val startTime: Long? = null,
    val endTime: Long? = null,

    /**
     * Possession Details:
     *  - Whether it is offensive or defensive (from the perspective of the user's team)
     *  - Whether players are in the box
     *  - Which players are on the field (using their id)
     *  - Which players are in the box (using their id)
     */
    val possessionType: PossessionType,
    val pitchState: PitchState = PitchState.FULL_LINE,

    /**
     * Uses IDs
     */
    val playersOnPitch: List<String>,
    val playersInBox: List<String>,

    // For defensive possessions indicates how many bludgers the team has
    val bludgerCount: Int? = null,

    // Indicates the result of the possession and the id of a player who caused a turnover if relevant
    var turnoverForcedBy: String? = null,
    var turnoverCommittedBy: String? = null,
    val result: PossessionResult,
    val shots: MutableList<Shot> = mutableListOf()
) {
    val goal: Shot?
        get() = if (shots.isNotEmpty() && result == PossessionResult.GOAL) shots.last() else null
}

enum class PossessionType { OFFENSE, DEFENSE }
enum class PitchState { FULL_LINE, MISSING_PLAYERS}
enum class PossessionResult { GOAL, CONCEDED_GOAL, TURNOVER }