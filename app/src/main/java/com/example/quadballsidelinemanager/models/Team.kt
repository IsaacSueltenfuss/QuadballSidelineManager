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

    /**
     * Generates a unique ID: acronym + initials + (optional) number.
     * Example: "txq" + "IS" -> "txqIS"
     */
    fun generatePlayerId(firstName: String, lastName: String): String {
        val initials = "${firstName.firstOrNull() ?: 'X'}${lastName.firstOrNull() ?: 'X'}"
            .uppercase()
        val baseId = "$teamAcronym$initials"

        // Find how many players already have these initials on this team
        val count = roster.count { it.id.startsWith(baseId) }

        return if (count == 0) baseId else "$baseId${count + 1}"
    }

    /**
     * Adds a new player to the roster using the ID generator.
     */
    fun addNewPlayer(name: String, number: Int, positions: MutableSet<QuadballPosition>, gender: GenderIdentity, photoPath: Int? = null) {
        val nameParts = name.split(" ")
        val firstName = nameParts.getOrNull(0) ?: "Player"
        val lastName = nameParts.getOrNull(1) ?: "Unknown"

        val posSet = positions.apply {
            if (QuadballPosition.KEEPER in this) add(QuadballPosition.CHASER)
        }

        val newId = generatePlayerId(firstName, lastName)

        val newPlayer = Player(
            id = newId,
            name = name,
            number = number,
            positions = posSet,
            primaryPosition = positions.first(),
            gender = gender,
            photoResId = photoPath,
            isActive = true
        )
        roster.add(newPlayer)
    }

    fun getPlayer(id: String): Player {
        return roster.find { it.id == id }
            ?: throw IllegalArgumentException("Player $id not found on ${this.teamName}")
    }

    /**
     * Distributes a completed possession to all players involved (Pitch + Box).
     */
    fun recordPossession(completedPossession: Possession) {
        activeLineupIds = completedPossession.playersOnPitch.toMutableSet()
        penaltyBoxIds = completedPossession.playersInBox.toMutableSet()
        val involvedIds = activeLineupIds + penaltyBoxIds

        roster.filter { it.id in involvedIds }.forEach { player ->
            player.possessions.add(completedPossession)
        }
    }
}