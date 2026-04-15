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
    var id: String,
    var name: String,
    var number: Int,
    var positions: Set<QuadballPosition>,
    var primaryPosition: QuadballPosition,
    var gender: GenderIdentity,
    val photoResId: Int? = null,
    var isActive: Boolean = true,

    var possessions: MutableList<Possession> = mutableListOf(),

    var caughtFlag: Boolean = false,

    var blueCards: Int = 0,
    var yellowCards: Int = 0,
    var redCards: Int = 0
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
        get() = possessions
            .filter { it.possessionType == PossessionType.DEFENSE && this.id in it.playersOnPitch }
            .mapNotNull { it.bludgerCount }
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
                    (this.id in it.playersOnPitch || this.id in it.playersInBox)
        }
    val plusValue: Int
        get() = goalsScoredWhileOnField.size
    val goalsConcededWhileOnField: List<Possession>
        get() = possessions.filter {
            it.result == PossessionResult.CONCEDED_GOAL &&
                    (this.id in it.playersOnPitch || this.id in it.playersInBox)
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
            .filter { this.id in it.playersInBox && it.result == PossessionResult.CONCEDED_GOAL }
            .size
    val goalsScoredWhileInBox: Int
        get() = offensivePossessions
            .filter { this.id in it.playersInBox && it.result == PossessionResult.GOAL }
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

    /**
     * FUTURE DIRECTIONS
     *  - Create stats for the possessions in which the player was on the pitch (not the box)
     *  - Create stats for the possessions in which none on the team were in the box
     *  - Revise plus/minus to account for these
     */

//    val activeOffensivePossessions: List<Possession>
//        get() = offensivePossessions.filter { this.id in it.playersOnPitch }
//    val totalActiveOffensivePossessions: Int
//        get() = activeOffensivePossessions.size
//
//    val activeDefensivePossessions: List<Possession>
//        get() = defensivePossessions.filter { this.id in it.playersOnPitch }
//    val totalActiveDefensivePossessions: Int
//        get() = activeDefensivePossessions.size

//    val goalsPerFullPossession: Double
//        get() = if (fullOffensivePossessions.isNotEmpty()) {
//            totalGoals.toDouble() / totalFullOffensivePossessions // This is wrong since the goals could include possessions where not the whole team was there
//        } else 0.0
//
//    val turnoversPerActivePossession: Double
//        get() = if (fullDefensivePossessions.isNotEmpty()) {
//            totalTurnovers.toDouble() / totalFullDefensivePossessions
//        } else 0.0
//
//    val goalsConcededOnFullPossessions: Int
//        get() = if (fullDefensivePossessions.isNotEmpty()) {
//            totalTurnovers
//        } else 0
}

enum class QuadballPosition { KEEPER, CHASER, BEATER, SEEKER, UTILITY }
enum class GenderIdentity { MALE, FEMALE, NON_BINARY, OTHER }