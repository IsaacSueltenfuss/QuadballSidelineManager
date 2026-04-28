package com.example.quadballsidelinemanager

import com.google.firebase.firestore.FieldValue
import android.util.Log
import com.example.quadballsidelinemanager.models.CardType
import com.example.quadballsidelinemanager.models.GameAction
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.example.quadballsidelinemanager.models.HoopID
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.QuadballPosition
import com.example.quadballsidelinemanager.models.ShotType
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class QuadballDBHelper() {
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val teamsCollection = "teams"
    private var gameListener: ListenerRegistration? = null

    fun initializeGameOnServer(teamId: String, gameId: String, opponent: String) {
        val initialData = hashMapOf(
            "opponent" to opponent,
            "status" to "LIVE",
            "startTime" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "teamScore" to 0,
            "oppScore" to 0
        )

        db.collection("teams").document(teamId)
            .collection("games").document(gameId)
            .set(initialData)
            .addOnSuccessListener { Log.d("FIREBASE", "Game $gameId initialized on server.") }
            .addOnFailureListener { e -> Log.e("FIREBASE", "Failed to initialize game", e) }
    }

    fun fetchGameMetadata(teamId: String, gameId: String, callback: (Map<String, Any>) -> Unit) {
        db.collection("teams").document(teamId)
            .collection("games").document(gameId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    callback(doc.data ?: emptyMap())
                }
            }
    }

    fun submitAction(teamId: String, gameId: String, action: GameAction) {
        val actionMap = hashMapOf(
            "actionType" to action.actionType,
            "timestamp" to action.timestamp,
            "possessionId" to action.possessionId
        )

        // Add specific fields based on the type of action
        when (action) {
            is GameAction.Goal -> {
                actionMap["playerId"] = action.playerId
                actionMap["assistId"] = action.assistId
                actionMap["type"] = action.type.name
                actionMap["hoop"] = action.hoop.name
            }
            is GameAction.MissedShot -> {
                actionMap["playerId"] = action.playerId
                actionMap["type"] = action.type.name
                actionMap["hoop"] = action.hoop.name
            }
            is GameAction.ConcededGoal -> {
                actionMap["type"] = action.type.name
                actionMap["hoop"] = action.hoop.name
            }
            is GameAction.Turnover -> {
                actionMap["playerId"] = action.playerId
                actionMap["forcedTurnover"] = action.isForced
            }
            is GameAction.Beat -> {
                actionMap["playerId"] = action.playerId
                actionMap["isOpponentBeat"] = action.isOpponentBeat
            }
            is GameAction.Penalty -> {
                actionMap["playerId"] = action.playerId
                actionMap["slotId"] = action.slotId
                actionMap["cardType"] = action.cardType.name
                actionMap["reason"] = action.reason
            }
            is GameAction.PenaltyEnded -> {
                actionMap["playerId"] = action.playerId
            }
            is GameAction.Substitution -> {
                actionMap["inPlayerId"] = action.inPlayerId
                actionMap["outPlayerId"] = action.outPlayerId
                actionMap["slot"] = action.slot
            }
            is GameAction.FlagCaught -> {
                actionMap["playerId"] = action.playerId
            }
        }

        db.collection("teams").document(teamId) // Add the teamId parent
            .collection("games").document(gameId)
            .collection("actions")
            .document(action.timestamp.toString())
            .set(actionMap)
            .addOnSuccessListener { Log.d("FIREBASE", "Sub-collection write SUCCESS") }
            .addOnFailureListener { e -> Log.e("FIREBASE", "Sub-collection write FAIL: ${e.message}") }
        Log.d("PLAYBACK", "Action recorded: ${action.javaClass.simpleName} at ${action.timestamp}")
    }

    fun listenToGame(teamId: String, gameId: String, onUpdate: (List<GameAction>) -> Unit) {
        gameListener?.remove()
        gameListener = db.collection("teams").document(teamId)
            .collection("games").document(gameId)
            .collection("actions")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("DB_HELPER", "Real-time listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val actions = snapshots.documents.mapNotNull { doc ->
                        mapDocumentToAction(doc)
                    }
                    onUpdate(actions)
                }
            }
    }

    fun stopListening() {
        gameListener?.remove()
        gameListener = null
    }

    fun checkGameJoinable(teamId: String, gameId: String, onResult: (String?) -> Unit) {
        db.collection("teams").document(teamId)
            .collection("games").document(gameId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onResult("Error: Game ID does not exist.")
                } else if (doc.getString("status") == "FINISHED") {
                    onResult("Error: This game has already been finalized.")
                } else {
                    onResult(null) // Success
                }
            }
    }

    private fun mapDocumentToAction(doc: com.google.firebase.firestore.DocumentSnapshot): GameAction? {
        val type = doc.getString("actionType") ?: return null
        val pId = doc.getString("possessionId")
        val timestamp = doc.getLong("timestamp") ?: 0L

        return try {
            when (type) {
                "GOAL" -> GameAction.Goal(
                    playerId = doc.getString("playerId") ?: "",
                    assistId = doc.getString("assistId"),
                    type = ShotType.valueOf(doc.getString("type") ?: "DUNK"),
                    hoop = HoopID.valueOf(doc.getString("hoop") ?: "TALL"),
                    pId = pId ?: ""
                )
                "MISSED SHOT" -> GameAction.MissedShot(
                    playerId = doc.getString("playerId") ?: "",
                    type = ShotType.valueOf(doc.getString("type") ?: "DUNK"),
                    hoop = HoopID.valueOf(doc.getString("hoop") ?: "TALL"),
                    pId = pId ?: ""
                )
                "CONCEDED GOAL" -> GameAction.ConcededGoal(
                    type = ShotType.valueOf(doc.getString("type") ?: "DUNK"),
                    hoop = HoopID.valueOf(doc.getString("hoop") ?: "TALL"),
                    pId = pId ?: ""
                )
                "TURNOVER" -> GameAction.Turnover(
                    playerId = doc.getString("playerId") ?: "",
                    isForced = doc.getBoolean("forcedTurnover") ?: false, // Note the key change here
                    pId = pId ?: ""
                )
                "BEAT" -> GameAction.Beat(
                    playerId = doc.getString("playerId") ?: "",
                    isOpponentBeat = doc.getBoolean("isOpponentBeat") ?: false,
                    pId = pId ?: ""
                )
                "PENALTY" -> GameAction.Penalty(
                    playerId = doc.getString("playerId") ?: "",
                    slotId = doc.getString("slotId") ?: "",
                    cardType = CardType.valueOf(doc.getString("cardType") ?: "YELLOW"),
                    reason = doc.getString("reason") ?: "",
                    pId = pId
                )
                "PENALTY ENDED" -> GameAction.PenaltyEnded(
                    playerId = doc.getString("playerId") ?: "",
                    slotId = doc.getString("slotId") ?: "",
                    pId = pId
                )
                "SUBSTITUTION" -> GameAction.Substitution(
                    inPlayerId = doc.getString("inPlayerId"),
                    outPlayerId = doc.getString("outPlayerId"),
                    slot = doc.getString("slot") ?: "",
                    pId = pId
                )
                "FLAG CAUGHT" -> GameAction.FlagCaught(
                    playerId = doc.getString("playerId") ?: "",
                    pId = pId
                )
                else -> null
            }
        } catch (e: Exception) {
            Log.e("DB_HELPER", "Error mapping action type: $type", e)
            null
        }
    }

    fun fetchTeamAuth(teamId: String, onResult: (String?, String?) -> Unit) {
        db.collection("teams").document(teamId).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val coach = document.getString("coachID")
                    val secondary = document.getString("secondaryCoachID")
                    onResult(coach, secondary)
                } else {
                    onResult(null, null)
                }
            }
            .addOnFailureListener {
                onResult(null, null)
            }
    }

    /**
     * Maps a Player object to a HashMap for Firestore.
     * Ensures consistent field names across all write operations.
     */
    private fun playerToMap(player: Player): HashMap<String, Any?> {
        return hashMapOf(
            "name" to player.name,
            "number" to player.number,
            "positions" to player.positions.map { it.name },
            "primaryPosition" to player.primaryPosition.name,
            "gender" to player.gender.name,
            "isActive" to player.isActive,
            "careerGoals" to player.totalGoals,
            "careerAssists" to player.totalAssists,
            "careerPossessions" to player.totalPossessions,
            "blueCards" to player.blueCards,
            "yellowCards" to player.yellowCards,
            "redCards" to player.redCards,
            "caughtFlag" to player.caughtFlag
        )
    }

    fun fetchRoster(teamId: String, callback: (List<Player>) -> Unit) {
        db.collection(teamsCollection).document(teamId).collection("players")
            .get()
            .addOnSuccessListener { result ->
                val players = result.documents.mapNotNull { doc ->
                    try {
                        val posStrings = doc.get("positions") as? List<String> ?: listOf()
                        Player(
                            id = doc.id,
                            name = doc.getString("name") ?: "Unknown",
                            number = doc.getLong("number")?.toInt() ?: 0,
                            positions = posStrings.map { QuadballPosition.valueOf(it) }.toSet(),
                            primaryPosition = QuadballPosition.valueOf(doc.getString("primaryPosition") ?: "CHASER"),
                            gender = GenderIdentity.valueOf(doc.getString("gender") ?: "OTHER"),
                            photoResId = doc.getLong("photoResId")?.toInt(),
                            isActive = doc.getBoolean("isActive") ?: true,
                            possessions = mutableListOf(),
                            caughtFlag = doc.getBoolean("caughtFlag") ?: false,
                            blueCards = doc.getLong("blueCards")?.toInt() ?: 0,
                            yellowCards = doc.getLong("yellowCards")?.toInt() ?: 0,
                            redCards = doc.getLong("redCards")?.toInt() ?: 0
                        )
                    } catch (e: Exception) {
                        Log.e("DB_HELPER", "Error mapping player ${doc.id}", e)
                        null
                    }
                }
                callback(players)
            }
    }

    /**
     * Adds a single player to the team roster.
     */
    fun addPlayerToTeam(teamId: String, player: Player, onComplete: () -> Unit = {}) {
        db.collection(teamsCollection).document(teamId)
            .collection("players").document(player.id)
            .set(playerToMap(player))
            .addOnSuccessListener {
                Log.d("DB_HELPER", "Player ${player.id} successfully added.")
                onComplete()
            }
            .addOnFailureListener { e -> Log.e("DB_HELPER", "Failed to add player", e) }
    }

    fun seedRoster(teamId: String, players: List<Player>, completion: () -> Unit = {}) {
        val batch = db.batch()
        val rosterRef = db.collection(teamsCollection).document(teamId).collection("players")

        players.forEach { player ->
            batch.set(rosterRef.document(player.id), playerToMap(player))
        }

        batch.commit()
            .addOnSuccessListener {
                Log.d("DB_HELPER", "Roster seeded successfully for $teamId")
                completion()
            }
            .addOnFailureListener { e -> Log.e("DB_HELPER", "Error seeding roster", e) }
    }

    fun finalizeGame(
        gameId: String,
        teamId: String,
        gameData: Map<String, Any>,
        roster: List<Player>,
        onSuccess: () -> Unit = {}
    ) {
        val batch = db.batch()
        val teamRef = db.collection(teamsCollection).document(teamId)
        val gameRef = teamRef.collection("games").document(gameId)

        batch.set(gameRef, gameData)

        roster.forEach { player ->
            val careerRef = teamRef.collection("players").document(player.id)
            batch.update(careerRef,
                "careerGoals", FieldValue.increment(player.totalGoals.toLong()),
                "careerAssists", FieldValue.increment(player.totalAssists.toLong()),
                "careerPossessions", FieldValue.increment(player.totalPossessions.toLong())
            )
        }

        batch.commit()
            .addOnSuccessListener {
                Log.d("DB_HELPER", "Game $gameId saved and stats updated.")
                onSuccess()
            }
            .addOnFailureListener { e -> Log.e("DB_HELPER", "Finalize failed", e) }
    }
}