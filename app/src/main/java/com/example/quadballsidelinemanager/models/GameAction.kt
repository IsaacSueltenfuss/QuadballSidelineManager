package com.example.quadballsidelinemanager.models

sealed class GameAction(
    val actionType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val possessionId: String?
) {
    // pId links the goal to currentPossession.id
    data class Goal(
        val playerId: String,
        val assistId: String?,
        val type: ShotType,
        val hoop: HoopID,
        val pId: String,
        val time: Long = System.currentTimeMillis()
    ) : GameAction("GOAL", timestamp = time, possessionId = pId)

    // Tracks misses to calculate shooting percentages
    data class MissedShot(
        val playerId: String,
        val type: ShotType,
        val hoop: HoopID,
        val pId: String
    ) : GameAction("MISSED SHOT", possessionId = pId)

    data class ConcededGoal(
        val type: ShotType,
        val hoop: HoopID,
        val pId: String
    ) : GameAction("CONCEDED GOAL", possessionId = pId)

    data class Turnover(
        val playerId: String,
        val isForced: Boolean,
        val pId: String
    ) : GameAction("TURNOVER", possessionId = pId)

    data class Beat(
        val playerId: String,
        val isOpponentBeat: Boolean,
        val pId: String
    ) : GameAction("BEAT", possessionId = pId)

    // Tracks cards; use the card types defined in Player.kt
    data class Penalty(
        val playerId: String,
        val slotId: String,
        val cardType: CardType,
        val reason: String,
        val pId: String?
    ) : GameAction("PENALTY", possessionId = pId)

    data class PenaltyEnded(
        val playerId: String,
        val slotId: String,
        val pId: String?
    ) : GameAction("PENALTY ENDED", possessionId = pId)

    data class Substitution(
        val inPlayerId: String?,
        val outPlayerId: String?,
        val slot: String,
        val pId: String?
    ) : GameAction("SUBSTITUTION", possessionId = pId)

    data class FlagCaught(
        val playerId: String,
        val pId: String?
    ) : GameAction("FLAG CAUGHT", possessionId = pId)
}

enum class CardType { BLUE, YELLOW, RED }