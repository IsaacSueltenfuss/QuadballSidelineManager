package com.example.quadballsidelinemanager.models

data class Player(
    /**
     * Basic details:
     *  - A unique ID (in the format of three letters for the team name and then the player's initials,
     * e.g. txqIS; if there are multiple people with the same initials on a team subsequent players
     * after the first will have a number appended like txqIS2).
     *  - Full Name
     *  - Jersey Number
     *  - Position (Chaser, Keeper, Beater, Seeker, Utility)
     *  - Gender (Male, Female, Non-binary, Other)
     *  - Photo Path
     *  - Whether the player is on the roster for this game
     *  - A list of all possessions that the player was involved in this game
     *  - Whether the player caught the flag runner this game
     *  - The number of cards (of each type) the player accrued this game
     */
    val id: String,
    val name: String,
    val number: Int,
    val positions: Set<QuadballPosition>,
    val primaryPosition: QuadballPosition,
    val gender: GenderIdentity,
    val photoResId: Int? = null,
    val isActive: Boolean = true,

    // Use List instead of MutableList to enforce immutability
    val possessions: List<Possession> = emptyList(),

    val caughtFlag: Boolean = false,

    val blueCards: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,

    val isBeatAmount: Int = 0,
    val beats: Int = 0
) {
    val firstName: String
        get() = name.split(" ").firstOrNull() ?: ""
    val lastName: String
        get() = name.split(" ").lastOrNull() ?: ""

    val totalPossessions: Int
        get() = possessions.size
    val fullCycles: Double
        get() = possessions.size / 2.0
    /**
     * Offensive Statistics:
     *  - The number of offensive possessions participated in
     *  - The goals scored by this player (each of which includes the type of goal and on which hoop)
     *  - The number of shots attempted
     *  - The number of assists
     *  - The number of turnovers caused by this player
     *  - This player's plus
     */
    val offensivePossessions: List<Possession>
        get() = possessions.filter { it.possessionType == PossessionType.OFFENSE }
    val totalOffensivePossessions: Int
        get() = offensivePossessions.size

    val shotsTaken: List<Shot>
        get() = offensivePossessions.flatMap { it.shots }.filter { it.shooterID == this.id }
    val totalShotsTaken: Int
        get() = shotsTaken.size
    val shotsPerPossession: Double
        get() = if (totalShotsTaken > 0 && totalOffensivePossessions > 0) totalShotsTaken.toDouble() / totalOffensivePossessions else 0.0

    val shotsOnTopHoop: Int
        get() = shotsTaken.count { it.hoopID == HoopID.TALL }
    val shotsOnMediumHoop: Int
        get() = shotsTaken.count { it.hoopID == HoopID.MEDIUM }
    val shotsOnSmallHoop: Int
        get() = shotsTaken.count { it.hoopID == HoopID.SMALL }

    val goals: List<Shot>
        get() = shotsTaken.filter { it.isGood }
    val totalGoals: Int
        get() = goals.size
    val dunkGoals: Int
        get() = goals.count { it.shotType == ShotType.DUNK }
    val finishGoals: Int
        get() = goals.count { it.shotType == ShotType.FINISH }
    val shotGoals: Int
        get() = goals.count { it.shotType == ShotType.SHOT }
    val miscGoals: Int
        get() = goals.count { it.shotType == ShotType.MISC }
    val goalsOnTopHoop: Int
        get() = goals.count { it.hoopID == HoopID.TALL }
    val goalsOnMediumHoop: Int
        get() = goals.count { it.hoopID == HoopID.MEDIUM }
    val goalsOnSmallHoop: Int
        get() = goals.count { it.hoopID == HoopID.SMALL }
    val goalsPerPossession: Double
        get() = if (totalGoals > 0 && totalOffensivePossessions > 0) totalGoals.toDouble() / totalOffensivePossessions else 0.0
    val shootingAccuracy: Double
        get() = if (totalShotsTaken > 0) (totalGoals.toDouble() / totalShotsTaken) * 100 else 0.0

    val assists: List<Shot>
        get() = offensivePossessions.mapNotNull { it.goal }.filter { it.assistantID == this.id }
    val totalAssists: Int
        get() = assists.size
    val assistsPerPossession: Double
        get() = if (totalAssists > 0 && totalOffensivePossessions > 0) totalAssists.toDouble() / totalOffensivePossessions else 0.0

    val turnovers: List<Possession>
        get() = offensivePossessions.filter { it.turnoverCommittedBy == this.id }
    val totalTurnovers: Int
        get() = turnovers.size
    val turnoversPerPossession: Double
        get() = if (totalTurnovers > 0 && totalOffensivePossessions > 0) totalTurnovers.toDouble() / totalOffensivePossessions else 0.0

    /**
     * DEFENSIVE STATISTICS:
     *  - A filtered list of all defensive possessions the player took part in
     *  - The player's forced turnovers on defense
     */
    val defensivePossessions: List<Possession>
        get() = possessions.filter { it.possessionType == PossessionType.DEFENSE }
    val totalDefensivePossessions: Int
        get() = defensivePossessions.size

    val forcedTurnovers: List<Possession>
        get() = defensivePossessions.filter { it.turnoverForcedBy == this.id }
    val totalForcedTurnovers: Int
        get() = forcedTurnovers.size
    val turnoversForcedPerPossession: Double
        get() = if (totalForcedTurnovers > 0 && totalDefensivePossessions > 0) totalForcedTurnovers.toDouble() / totalDefensivePossessions else 0.0

    val dodgeballsOnDefense: List<Int>
        get() = possessions.filter { pos ->
            pos.possessionType == PossessionType.DEFENSE &&
                    pos.playersOnPitch.any { (slotId, playerId) ->
                        playerId == this.id && slotId.contains("beater", ignoreCase = true)
                    }
        }.mapNotNull { it.bludgerCount }
    val numDodgeballsPerPossession: Double
        get() = if (dodgeballsOnDefense.isNotEmpty()) dodgeballsOnDefense.average() else 0.0

    /**
     * ADVANCED STATISTICS:
     *  - Goals that were scored while the player was on the pitch
     *  - Goals conceded while the player was on the pitch
     *  - Plus/minus for the player
     *  - Plus/minus per possession (both offense and defense)
     *  - Plus/minus per 20 possessions
     */
    val goalsScoredWhileOnField: List<Possession>
        get() = possessions.filter {
            it.result == PossessionResult.GOAL &&
                    (it.playersOnPitch.containsValue(this.id) || it.playersInBox.containsValue(this.id))
        }
    val plusValue: Int
        get() = goalsScoredWhileOnField.size
    val goalsConcededWhileOnField: List<Possession>
        get() = possessions.filter {
            it.result == PossessionResult.CONCEDED_GOAL &&
                    (it.playersOnPitch.containsValue(this.id) || it.playersInBox.containsValue(this.id))
        }
    val minusValue: Int
        get() = goalsConcededWhileOnField.size
    val plusMinus: Int
        get() = plusValue - minusValue
    val plusMinusPerPossession: Double
        get() = if (fullCycles > 0) plusMinus.toDouble() / fullCycles else 0.0
    val plusMinusPer20Possessions: Double
        get() = plusMinusPerPossession * 20

    val offensiveEfficiency: Double
        get() = if (totalOffensivePossessions > 0) (totalGoals + totalAssists).toDouble() / totalOffensivePossessions else 0.0

    /**
     * CARD RELATED STATISTICS:
     *  - Goals that were conceded while the player was in the box
     *  - Goals that were scored while the player was in the box
     *  - The player's overall impact while in the box
     *  - The possessions in which none of the team was in the box
     *
     */
    val goalsConcededWhileInBox: Int
        get() = defensivePossessions
            .filter { it.playersInBox.containsValue(this.id) && it.result == PossessionResult.CONCEDED_GOAL }
            .size
    val goalsScoredWhileInBox: Int
        get() = offensivePossessions
            .filter { it.playersInBox.containsValue(this.id) && it.result == PossessionResult.GOAL }
            .size
    val boxImpact: Int
        get() = goalsScoredWhileInBox - goalsConcededWhileInBox

    val fullOffensivePossessions: List<Possession>
        get() = offensivePossessions.filter { it.playersInBox.isEmpty() }
    val totalFullOffensivePossessions: Int
        get() = fullOffensivePossessions.size

    val fullDefensivePossessions: List<Possession>
        get() = defensivePossessions.filter { it.playersInBox.isEmpty() }
    val totalFullDefensivePossessions: Int
        get() = fullDefensivePossessions.size

    companion object {
        /**
         * Generates a unique ID: acronym + initials + (optional) number.
         * Example: "txq" + "IS" -> "txqIS"
         */
        fun generateId(
            teamAcronym: String,
            firstName: String,
            lastName: String,
            existingRoster: List<Player>
        ): String {
            val initials = "${firstName.firstOrNull() ?: 'X'}${lastName.firstOrNull() ?: 'X'}"
                .uppercase()
            val baseId = "$teamAcronym$initials"

            // Count how many people ALREADY have this ID base
            val count = existingRoster.count { it.id.startsWith(baseId) }

            return if (count == 0) baseId else "$baseId${count + 1}"
        }

        fun create(
            name: String,
            number: Int,
            primaryPosition: QuadballPosition,
            positions: MutableSet<QuadballPosition>,
            gender: GenderIdentity,
            teamAcronym: String,
            existingRoster: List<Player>
        ): Player {
            val nameParts = name.split(" ")
            val first = nameParts.getOrNull(0) ?: "Player"
            val last = nameParts.getOrNull(1) ?: "Unknown"

            val finalPositions = positions.toMutableSet().apply {
                if (QuadballPosition.KEEPER in this) add(QuadballPosition.CHASER)
            }

            return Player(
                id = generateId(teamAcronym, first, last, existingRoster),
                name = name,
                number = number,
                positions = finalPositions,
                primaryPosition = primaryPosition,
                gender = gender
            )
        }
    }
}

enum class QuadballPosition { KEEPER, CHASER, BEATER, SEEKER }
enum class GenderIdentity { MALE, FEMALE, NON_BINARY, OTHER }
