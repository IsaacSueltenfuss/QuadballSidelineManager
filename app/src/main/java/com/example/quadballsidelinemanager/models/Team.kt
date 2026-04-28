package com.example.quadballsidelinemanager.models

import android.util.Log

data class Team(
    val teamName: String,
    val teamAcronym: String, // e.g. "txq"
    val roster: MutableList<Player> = mutableListOf(),

    // Tracking the current 7 people on the pitch for the Live Game
    var activeLineupIds: MutableSet<String> = mutableSetOf(),
    var penaltyBoxIds: MutableSet<String> = mutableSetOf()
) {

    fun getPlayer(id: String): Player {
        return roster.find { it.id == id }
            ?: throw IllegalArgumentException("Player $id not found on ${this.teamName}")
    }

    /**
     * Distributes a completed possession to all players involved (Pitch + Box).
     * Uses immutable updates to ensure StateFlow observers detect changes.
     */
    fun recordPossession(completedPossession: Possession) {
        activeLineupIds = completedPossession.playersOnPitch.values.toMutableSet()
        penaltyBoxIds = completedPossession.playersInBox.values.toMutableSet()
        val involvedIds = activeLineupIds + penaltyBoxIds

        Log.d("POSSESSION_DEBUG", "FINALIZING: ${completedPossession.id} | Result: ${completedPossession.result} | Involved IDs: $involvedIds")

        for (i in roster.indices) {
            val player = roster[i]
            if (player.id in involvedIds) {
                Log.d("POSSESSION_DEBUG", "Successfully added possession to player: ${player.lastName}")
                // Create a NEW list instance and a NEW player instance
                val updatedPossessions = player.possessions.toMutableList().apply { add(completedPossession) }
                roster[i] = player.copy(possessions = updatedPossessions)
            }
        }
    }

    /**
     * Removes a possession from all involved players (Pitch + Box).
     * Uses immutable updates to ensure StateFlow observers detect changes.
     */
    fun deletePossession(possession: Possession) {
        activeLineupIds = possession.playersOnPitch.values.toMutableSet()
        penaltyBoxIds = possession.playersInBox.values.toMutableSet()
        val involvedIds = activeLineupIds + penaltyBoxIds

        for (i in roster.indices) {
            val player = roster[i]
            if (player.id in involvedIds) {
                Log.d("UNDO_DEBUG", "Player ${player.id} has ${player.possessions.size} possessions played.")
                // Filter into a new list and create a NEW player instance
                val updatedPossessions = player.possessions.filter { it.id != possession.id }.toMutableList()
                roster[i] = player.copy(possessions = updatedPossessions)
                Log.d("UNDO_DEBUG", "Player ${player.id} had possession ${possession.id} removed. Now: ${roster[i].possessions.size}")
            }
        }
    }
}
