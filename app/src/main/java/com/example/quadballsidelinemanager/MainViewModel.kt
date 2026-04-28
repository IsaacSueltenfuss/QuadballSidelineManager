package com.example.quadballsidelinemanager

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.quadballsidelinemanager.models.CardType
import com.example.quadballsidelinemanager.models.GameAction
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.example.quadballsidelinemanager.models.HoopID
import com.example.quadballsidelinemanager.models.PitchState
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.Possession
import com.example.quadballsidelinemanager.models.PossessionResult
import com.example.quadballsidelinemanager.models.PossessionType
import com.example.quadballsidelinemanager.models.QuadballPosition
import com.example.quadballsidelinemanager.models.Shot
import com.example.quadballsidelinemanager.models.ShotType
import com.example.quadballsidelinemanager.models.Team
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Indicates the progress in recording a goal or other complex game actions.
 */
sealed class RecordingState {
    object Idle : RecordingState()
    data class SelectingAction(val player: Player, val slotId: String) : RecordingState()
    data class SelectingShotResult(val player: Player, val slotId: String) : RecordingState()
    data class SelectingHoop(val player: Player, val isGood: Boolean) : RecordingState()
    data class SelectingShotType(val player: Player, val hoopId: HoopID, val isGood: Boolean) : RecordingState()
    data class SelectingAssistantDecision(val player: Player, val hoop: HoopID, val type: ShotType) : RecordingState()
    data class SelectingAssistant(val player: Player, val hoop: HoopID, val type: ShotType) : RecordingState()
    object RecordingBludgers : RecordingState()
    data class SelectingConcededGoalType(val hoopId: HoopID) : RecordingState()
    data class SelectingPenaltyType(val player: Player, val slotId: String) : RecordingState()
}

/**
 * Different sorting possibilities for the roster.
 */
enum class SortType { NUMBER, NAME, POSITION, GENDER, POSSESSIONS, PLUS_MINUS }

/**
 * Different game phases.
 */
enum class GamePhase { DEFAULT, SEEKER_FLOOR, TIMEOUT }

/**
 * Interaction enablement levels for the pitch UI.
 */
enum class EnableType { GAME_IN_PROGRESS, GAME_LOADED, NONE }

/**
 * Authorization levels for users.
 */
enum class AuthType { PRIMARY_COACH, SECONDARY_COACH, NONE }

/**
 * Main ViewModel for managing game state, roster, and live synchronization.
 */
class MainViewModel(private val authUser: AuthUser) : ViewModel() {

    // Database helper for Firestore interactions
    private val dbHelper = QuadballDBHelper()

    // Currently authenticated user information
    private val _currentAuthUser = MutableStateFlow(invalidUser)
    val currentAuthUser: StateFlow<User> = _currentAuthUser.asStateFlow()

    // Whether the flag (snitch) has been caught in the current game
    private val _isFlagCaught = MutableStateFlow(false)
    val isFlagCaught: StateFlow<Boolean> = _isFlagCaught.asStateFlow()

    // Full chronological log of all game actions (used for sync and reconstruction)
    private val _gameLog = MutableStateFlow<List<GameAction>>(emptyList())
    val gameLog: StateFlow<List<GameAction>> = _gameLog.asStateFlow()

    // Live score for the tracking team
    private val _currentTeamScore = MutableStateFlow(0)
    val currentTeamScore: StateFlow<Int> = _currentTeamScore.asStateFlow()

    // Live score for the opposing team
    private val _oppTeamScore = MutableStateFlow(0)
    val oppTeamScore: StateFlow<Int> = _oppTeamScore.asStateFlow()

    // Sequential counter to generate unique possession IDs
    private var possessionCounter = 0

    // Reference to the possession immediately preceding the current one (for undo)
    private var priorPossession: Possession? = null
    private var priorPossessionType: PossessionType? = null

    // Name of the opposing team
    private val _opposingTeam = MutableStateFlow("")
    val opposingTeam: StateFlow<String> = _opposingTeam.asStateFlow()

    // Toggle for enforcing gender maximums on the pitch
    private val _isGenderRuleEnabled = MutableStateFlow(true)
    val isGenderRuleEnabled = _isGenderRuleEnabled.asStateFlow()

    // Toggle for enforcing position-specific slot requirements
    private val _isPositionRuleEnabled = MutableStateFlow(true)
    val isPositionRuleEnabled = _isPositionRuleEnabled.asStateFlow()

    // Toggle for showing the bludger count dialog on defensive transitions
    private val _beaterStatsEnabled = MutableStateFlow(true)
    val beaterStatsEnabled = _beaterStatsEnabled.asStateFlow()

    // Current enablement state of the pitch interaction UI
    private val _pitchEnabled = MutableStateFlow(EnableType.NONE)
    val pitchEnabled: StateFlow<EnableType> = _pitchEnabled.asStateFlow()

    // Current phase of the game (Standard, Seeker Floor, etc.)
    private val _gamePhase = MutableStateFlow(GamePhase.DEFAULT)
    val gamePhase: StateFlow<GamePhase> = _gamePhase.asStateFlow()

    // Current position-based filtering for the player lists
    private val _positionFilter = MutableStateFlow<QuadballPosition?>(null)
    val positionFilter: StateFlow<QuadballPosition?> = _positionFilter.asStateFlow()

    // Filter to show only players marked as active
    private val _onlyActiveFilter = MutableStateFlow(false)
    val onlyActiveFilter: StateFlow<Boolean> = _onlyActiveFilter.asStateFlow()

    // Filter to show only players currently assigned to pitch slots
    private val _onlyPlayingFilter = MutableStateFlow(false)
    val onlyPlayingFilter: StateFlow<Boolean> = _onlyPlayingFilter.asStateFlow()

    // Current sorting strategy for player lists
    private val _sortFilter = MutableStateFlow(SortType.POSITION)
    val sortFilter: StateFlow<SortType> = _sortFilter.asStateFlow()

    // Master list of all players on the team roster
    private val _allPlayers = MutableStateFlow<List<Player>>(emptyList())
    val allPlayers: StateFlow<List<Player>> = _allPlayers.asStateFlow()

    // Mapping of pitch slot IDs to the player currently in that slot
    private val _pitchOccupants = MutableStateFlow<Map<String, Player?>>(emptyMap())
    val pitchOccupants: StateFlow<Map<String, Player?>> = _pitchOccupants.asStateFlow()

    // Filtered and sorted list for the roster management tab
    val rosterTabPlayers: StateFlow<List<Player>> = combine(
        _allPlayers, _positionFilter, _onlyActiveFilter, _sortFilter
    ) { players, pos, onlyActive, sort ->
        players.filter { player ->
            (pos == null || pos in player.positions) && (!onlyActive || player.isActive)
        }.sortedWith(getComparator(sort))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Filtered and sorted list for the bench (active players not on the pitch)
    val benchPlayers = combine(
        _allPlayers,
        _pitchOccupants,
        _positionFilter,
        _onlyPlayingFilter,
        _sortFilter
    ) { all, occupants, pos, onlyPlaying, sort ->
        val onPitchIds = occupants.values.filterNotNull().map { it.id }.toSet()

        val filteredList = all.filter { player ->
            val statusMatches = if (onlyPlaying) {
                onPitchIds.contains(player.id)
            } else {
                !onPitchIds.contains(player.id)
            }

            val positionMatches = pos == null || pos in player.positions
            val isEjected = (player.redCards > 0) || (player.yellowCards > 2)

            player.isActive && statusMatches && positionMatches && !isEjected
        }
        filteredList.sortedWith(getComparator(sort))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Mapping of slot IDs to players currently serving a penalty
    private val _playersInBox = MutableStateFlow<Map<String, Player?>>(emptyMap())
    val playersInBox: StateFlow<Map<String, Player?>> = _playersInBox.asStateFlow()

    // Set of IDs for players who have been ejected (Red card or 2 Yellows)
    private val _ejectedPlayerIds = MutableStateFlow<Set<String>>(emptySet())
    val ejectedPlayerIds: StateFlow<Set<String>> = _ejectedPlayerIds.asStateFlow()

    // State of the bench expansion UI element
    private val _isBenchExpanded = MutableStateFlow(true)
    val isBenchExpanded: StateFlow<Boolean> = _isBenchExpanded.asStateFlow()

    // Slot ID target for a pending substitution
    private val _pendingSlot = MutableStateFlow<String?>(null)
    val pendingSlot: StateFlow<String?> = _pendingSlot.asStateFlow()

    // Player selected for a pending substitution
    private val _pendingPlayer = MutableStateFlow<Player?>(null)
    val pendingPlayer: StateFlow<Player?> = _pendingPlayer.asStateFlow()

    // Current state in the multi-step action recording flow
    private val _uiState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val uiState = _uiState.asStateFlow()

    // Team model containing roster and possession history
    private val _currentTeam = MutableStateFlow(Team("New Team", "TBD"))
    val currentTeam = _currentTeam.asStateFlow()

    // Flow for emitting short-lived event messages (e.g. "Goal Recorded!")
    private val _statEventMessage = MutableSharedFlow<String>()
    val statEventMessage: SharedFlow<String> = _statEventMessage

    // Data object for the current active possession
    private var currentPossession: Possession? = null

    // Whether the current possession is Offensive or Defensive
    private val _currentPossessionType = MutableStateFlow(PossessionType.OFFENSE)
    val currentPossessionType: StateFlow<PossessionType> = _currentPossessionType.asStateFlow()

    // Unique identifier for the current game session
    private val _gameID = MutableStateFlow("")
    val gameID: StateFlow<String> = _gameID.asStateFlow()

    // Authorization level of the current user for the selected team
    private val _userAuthLevel = MutableStateFlow(AuthType.NONE)
    val userAuthLevel: StateFlow<AuthType> = _userAuthLevel.asStateFlow()

    init {
        authUser.liveUser.observeForever { user ->
            _currentAuthUser.value = user
            Log.d("AUTH_SYNC", "Detected Auth Change: ${user.name} (${user.uid})")

            if (!user.isInvalid()) {
                dbHelper.fetchTeamAuth("txq") { primary, secondary ->
                    val userId = currentAuthUser.value.uid
                    _userAuthLevel.value = when (userId) {
                        primary -> AuthType.PRIMARY_COACH
                        secondary -> AuthType.SECONDARY_COACH
                        else -> AuthType.NONE
                    }
                    loadRoster()
                    Log.d("AUTH_CHECK", "User $userId authorized: ${_userAuthLevel.value}")
                }
            } else {
                _userAuthLevel.value = AuthType.NONE
                resetGame(false)
            }
        }
    }

    /**
     * Loads the team roster from the database.
     */
    fun loadRoster() {
        val teamId = "txq"
        dbHelper.fetchRoster(teamId) { playerList ->
            val team = Team(
                teamName = "Texas Quadball",
                teamAcronym = teamId,
                roster = playerList.toMutableList()
            )
            _currentTeam.value = team
            _allPlayers.value = playerList
            Log.d("AUTH", "Team initialized with ${playerList.size} players.")
        }
    }

    /**
     * Maps a pitch slot ID to its required QuadballPosition.
     */
    fun getRequiredPositionForSlot(slotId: String): QuadballPosition {
        return when {
            slotId.contains("keeper", ignoreCase = true) -> QuadballPosition.KEEPER
            slotId.contains("chaser", ignoreCase = true) -> QuadballPosition.CHASER
            slotId.contains("beater", ignoreCase = true) -> QuadballPosition.BEATER
            slotId.contains("seeker", ignoreCase = true) -> QuadballPosition.SEEKER
            else -> QuadballPosition.CHASER
        }
    }

    /**
     * Toggles the active status for a player and removes them from the pitch if they become inactive.
     */
    fun toggleActivePlayerStatus(player: Player) {
        val updatedPlayer = player.copy(isActive = !player.isActive)
        updatePlayerInState(updatedPlayer)

        if (!updatedPlayer.isActive) {
            val currentOccupants = _pitchOccupants.value.toMutableMap()
            val slot = currentOccupants.entries.find { it.value?.id == updatedPlayer.id }?.key
            if (slot != null) {
                currentOccupants[slot] = null
                _pitchOccupants.value = currentOccupants
            }
        }
    }

    /**
     * Creates a sorting comparator based on SortType.
     */
    private fun getComparator(type: SortType): Comparator<Player> {
        return when (type) {
            SortType.POSITION -> compareBy<Player> { getPositionPriority(it) }
                .thenByDescending { it.totalPossessions }
                .thenBy { it.lastName }
                .thenBy { it.firstName }
            SortType.POSSESSIONS -> compareByDescending<Player> { it.totalPossessions }
                .thenBy { getPositionPriority(it) }
                .thenBy { it.lastName }
                .thenBy { it.firstName }
            SortType.PLUS_MINUS -> compareByDescending<Player> { it.plusMinus }
                .thenBy { getPositionPriority(it) }
                .thenBy { it.lastName }
                .thenBy { it.firstName }
            SortType.NUMBER -> compareBy<Player> { it.number }
                .thenBy { getPositionPriority(it) }
            SortType.NAME -> compareBy<Player> { it.lastName }
                .thenBy { it.firstName }
                .thenBy { getPositionPriority(it) }
            SortType.GENDER -> compareBy<Player> { it.gender }
                .thenBy { getPositionPriority(it) }
                .thenBy { it.lastName }
                .thenBy { it.firstName }
        }
    }

    /**
     * Returns a priority value for sorting positions.
     */
    private fun getPositionPriority(player: Player): Int {
        return when (player.primaryPosition) {
            QuadballPosition.KEEPER -> 1
            QuadballPosition.CHASER -> 2
            QuadballPosition.BEATER -> 3
            QuadballPosition.SEEKER -> 4
            else -> 5
        }
    }

    /** Updates the sort filter. */
    fun updateSort(type: SortType) {
        _sortFilter.value = type
    }

    /** Updates the position filter. */
    fun setPositionFilter(pos: QuadballPosition?) {
        _positionFilter.value = pos
    }

    /** Toggles the active filter. */
    fun toggleOnlyActiveFilter() {
        _onlyActiveFilter.value = !_onlyActiveFilter.value
    }

    /** Toggles the visibility of the bench. */
    fun toggleBenchVisibility() {
        _isBenchExpanded.value = !_isBenchExpanded.value
    }

    /**
     * Core substitution logic. Assigns a player to a specific slot on the pitch.
     */
    fun assignPlayerToSlot(player: Player, targetSlotId: String, isLive: Boolean) {
        val outPlayer = _pitchOccupants.value[targetSlotId]
        val requiredPos = getRequiredPositionForSlot(targetSlotId)

        if (_isPositionRuleEnabled.value && !player.positions.contains(requiredPos)) {
            viewModelScope.launch {
                _statEventMessage.emit("${player.lastName} is not registered as a ${requiredPos.name}")
            }
            resetRecordingState()
            return
        }

        if (!canAddPlayerToPitch(player, targetSlotId)) {
            viewModelScope.launch {
                _statEventMessage.emit("Cannot add ${player.lastName}: Already 4 players of gender ${player.gender} on pitch.")
            }
            resetRecordingState()
            return
        }

        val currentMap = _pitchOccupants.value.toMutableMap()
        val existingSlot = currentMap.entries.find { it.value?.id == player.id }?.key
        if (existingSlot != null) currentMap[existingSlot] = null

        currentMap[targetSlotId] = player
        _pitchOccupants.value = currentMap

        currentPossession = currentPossession?.copy(playersOnPitch = capturePlayersOnPitch())

        recordAction(
            GameAction.Substitution(
                inPlayerId = player.id,
                outPlayerId = outPlayer?.id,
                slot = targetSlotId,
                pId = currentPossession?.id
            ),
            isLive
        )

        clearPendingStates()

        val totalOnField = currentMap.values.filterNotNull().size
        val maxPlayers = if (gamePhase.value == GamePhase.SEEKER_FLOOR) 7 else 6
        _isBenchExpanded.value = totalOnField < maxPlayers
    }

    /**
     * Resets temporary states related to the substitution process.
     */
    fun clearPendingStates() {
        _pendingPlayer.value = null
        _pendingSlot.value = null
        _positionFilter.value = null
    }

    /**
     * Initiates the substitution process from a player click.
     */
    fun startSubProcessPlayer(player: Player, isDragging: Boolean) {
        _pendingPlayer.value = player
        _pendingSlot.value = null
        if (!isDragging) _isBenchExpanded.value = false
    }

    /**
     * Initiates the substitution process from an empty slot click.
     */
    fun startSubProcessSlot(slotId: String, requiredPos: QuadballPosition) {
        _pendingSlot.value = slotId
        _pendingPlayer.value = null
        _positionFilter.value = requiredPos
        _isBenchExpanded.value = true
    }

    /**
     * Swaps two players already on the pitch.
     */
    fun swapPlayers(sourceSlotId: String, targetSlotId: String) {
        val currentMap = _pitchOccupants.value.toMutableMap()
        val sourcePlayer = currentMap[sourceSlotId]
        val targetPlayer = currentMap[targetSlotId]

        currentMap[targetSlotId] = sourcePlayer
        currentMap[sourceSlotId] = targetPlayer

        _pitchOccupants.value = currentMap
        currentPossession = currentPossession?.copy(playersOnPitch = capturePlayersOnPitch())

        sourcePlayer?.let {
            recordAction(GameAction.Substitution(it.id, targetPlayer?.id, targetSlotId, currentPossession?.id))
        }
        clearPendingStates()
    }

    /** Updates the opposing team name. */
    fun updateOpposingTeam(team: String) {
        _opposingTeam.value = team
    }

    /** Updates the current game phase (e.g., seeker floor). */
    fun updateGamePhase(phase: GamePhase) {
        _gamePhase.value = phase
    }

    /** Toggles the filter to show only playing players. */
    fun toggleOnlyPlayingFilter() {
        _onlyPlayingFilter.value = !_onlyPlayingFilter.value
    }

    /** Toggles whether the current team is on offense or defense. */
    fun toggleStartingSide() {
        _currentPossessionType.value = when (_currentPossessionType.value) {
            PossessionType.OFFENSE -> PossessionType.DEFENSE
            PossessionType.DEFENSE -> PossessionType.OFFENSE
            else -> PossessionType.OFFENSE
        }
    }

    /** Resets the recording state back to Idle. */
    fun resetRecordingState() {
        _uiState.value = RecordingState.Idle
        _pendingSlot.value = null
        _pendingPlayer.value = null
        _positionFilter.value = null
    }

    /** Initiates recording an action for a specific player. */
    fun initiateAction(player: Player, slotId: String) {
        _uiState.value = RecordingState.SelectingAction(player, slotId)
    }

    /** Advances the action selection flow. */
    fun selectAction(action: String) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingAction) {
            when (action.uppercase()) {
                "SHOT" -> _uiState.value = RecordingState.SelectingShotResult(currentState.player, currentState.slotId)
                "TURNOVER" -> finalizeTurnover(currentState.player, true)
                "TURNOVER FORCED" -> finalizeTurnoverForced(currentState.player, true)
                "CAUGHT FLAG" -> finalizeFlagCatch(currentState.player, true)
                "PENALTY" -> _uiState.value = RecordingState.SelectingPenaltyType(currentState.player, currentState.slotId)
                "PENALTY ENDED" -> finalizePenaltyEnded(currentState.slotId, true)
                else -> _uiState.value = RecordingState.Idle
            }
        }
    }

    /** Finalizes a flag catch by the current team. */
    private fun finalizeFlagCatch(player: Player, isLive: Boolean) {
        val activePossession = currentPossession ?: return
        
        val updatedPlayer = player.copy(caughtFlag = true)
        updatePlayerInState(updatedPlayer)
        
        val action = GameAction.FlagCaught(updatedPlayer.id, activePossession.id)

        _currentTeamScore.value += 35
        _gamePhase.value = GamePhase.DEFAULT
        _isFlagCaught.value = true
        if (isLive) {
            recordAction(action, isLive)
            resetRecordingState()
            _statEventMessage.tryEmit("Snitch Caught by ${updatedPlayer.lastName}!")
        }
    }

    /** Finalizes a flag catch by the opponent. */
    fun finalizeOpponentFlagCatch(isLive: Boolean) {
        _oppTeamScore.value += 35
        _gamePhase.value = GamePhase.DEFAULT
        _isFlagCaught.value = true
        if (isLive) {
            recordAction(GameAction.FlagCaught("OPPONENT", currentPossession?.id ?: ""), isLive)
            resetRecordingState()
            viewModelScope.launch { _statEventMessage.emit("Opponent Flag Catch: +35 points") }
        }
    }

    /** Records a penalty for a player and manages the penalty box. */
    fun finalizePenalty(player: Player, cardType: CardType, slotId: String, isLive: Boolean) {
        val activePossession = currentPossession ?: return
        
        val updatedPlayer = when (cardType) {
            CardType.BLUE -> player.copy(blueCards = player.blueCards + 1)
            CardType.YELLOW -> player.copy(yellowCards = player.yellowCards + 1)
            CardType.RED -> player.copy(redCards = player.redCards + 1)
        }
        
        // Update player in state first
        updatePlayerInState(updatedPlayer)

        val action = GameAction.Penalty(updatedPlayer.id, slotId, cardType, "", activePossession.id)
        recordAction(action, isLive)

        // Then update the penalty box UI state
        _playersInBox.value = _playersInBox.value + (slotId to updatedPlayer)

        if ((cardType == CardType.YELLOW && updatedPlayer.yellowCards > 2) || cardType == CardType.RED) {
            _ejectedPlayerIds.value += updatedPlayer.id
            removePlayerFromField(updatedPlayer, isLive)
        }
        resetRecordingState()
    }

    /** Releases a player from the penalty box. */
    fun finalizePenaltyEnded(slotId: String, isLive: Boolean) {
        val activePossession = currentPossession ?: return
        val player = _playersInBox.value[slotId] ?: return
        val action = GameAction.PenaltyEnded(player.id, slotId, activePossession.id)
        recordAction(action, isLive)
        _playersInBox.value = _playersInBox.value - slotId

        refreshData()
        resetRecordingState()
    }

    /** Removes a player from the pitch. */
    fun removePlayerFromField(player: Player, isLive: Boolean) {
        val activePossession = currentPossession ?: return
        val currentMap = _pitchOccupants.value.toMutableMap()
        val positionEntry = currentMap.entries.find { it.value?.id == player.id }

        positionEntry?.let { (position, _) ->
            val action = GameAction.Substitution(null, player.id, position, activePossession.id)
            recordAction(action, isLive)
            currentMap[position] = null
            _pitchOccupants.value = currentMap
        }
        _isBenchExpanded.value = true
    }

    /** Records a beat action for a beater. */
    fun finalizeBeat(player: Player, isOpponentBeat: Boolean, isLive: Boolean = true) {
        val updatedPlayer = player.copy(beats = player.beats + 1)
        updatePlayerInState(updatedPlayer)
        
        if (isLive) {
            val activePId = currentPossession?.id ?: ""
            recordAction(GameAction.Beat(updatedPlayer.id, isOpponentBeat, activePId), isLive)
            val message = if (isOpponentBeat) "Beat by ${updatedPlayer.lastName}" else "${updatedPlayer.lastName} was Beat"
            viewModelScope.launch { _statEventMessage.emit(message) }
        }
    }

    /** Sets whether a shot was successful. */
    fun setShotResult(isGood: Boolean) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingShotResult) {
            _uiState.value = RecordingState.SelectingHoop(currentState.player, isGood)
        }
    }

    /** Records which hoop was targeted for a shot. */
    fun selectHoop(hoopId: HoopID) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingHoop) {
            _uiState.value = RecordingState.SelectingShotType(currentState.player, hoopId, currentState.isGood)
        }
    }

    /** Records the type of shot and proceeds to assistant selection or finalizes a miss. */
    fun selectShotType(type: ShotType) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingShotType) {
            if (currentState.isGood) {
                _uiState.value = RecordingState.SelectingAssistantDecision(
                    currentState.player, currentState.hoopId, type
                )
            } else {
                recordMissedShot(currentState.player, currentState.hoopId, type, true)
                _uiState.value = RecordingState.Idle
            }
        }
    }

    /** Records a missed shot. */
    private fun recordMissedShot(player: Player, hoop: HoopID, type: ShotType, isLive: Boolean) {
        val activePossession = currentPossession ?: return

        activePossession.shots.add(Shot(player.id, null, type, hoop, isGood = false))

        if (isLive) {
            recordAction(GameAction.MissedShot(player.id, type, hoop, activePossession.id), isLive)
            viewModelScope.launch { _statEventMessage.emit("Missed shot by ${player.lastName} recorded.") }
        }
    }

    /** Sets whether an assistant should be recorded for a goal. */
    fun setAssistantRequired(required: Boolean) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingAssistantDecision) {
            if (required) {
                _uiState.value = RecordingState.SelectingAssistant(
                    currentState.player, currentState.hoop, currentState.type
                )
            } else {
                completeGoal(null)
            }
        }
    }

    /** Finalizes a goal with or without an assistant. */
    fun completeGoal(assistant: Player?) {
        val currentState = _uiState.value
        when (currentState) {
            is RecordingState.SelectingAssistant -> {
                saveGoal(currentState.player, assistant, currentState.hoop, currentState.type, true)
                _uiState.value = RecordingState.Idle
            }

            is RecordingState.SelectingAssistantDecision -> {
                saveGoal(currentState.player, assistant, currentState.hoop, currentState.type, true)
                _uiState.value = RecordingState.Idle
            }

            else -> Log.e("ViewModel", "completeGoal called from unexpected state: $currentState")
        }
    }

    /** Saves a successful goal and transitions possession. */
    fun saveGoal(player: Player, assistant: Player?, hoop: HoopID, type: ShotType, isLive: Boolean) {
        val active = currentPossession ?: return

        currentPossession = active.copy(result = PossessionResult.GOAL)

        currentPossession?.shots?.add(Shot(player.id, assistant?.id, type, hoop, isGood = true))
        _currentTeamScore.value += 10

        if (isLive) {
            recordAction(GameAction.Goal(player.id, assistant?.id, type, hoop, active.id), isLive)
            transitionToDefense(isLive)
        }
    }

    /** Records an unforced turnover and transitions possession. */
    fun finalizeTurnover(player: Player, isLive: Boolean) {
        val activePossession = currentPossession ?: return

        currentPossession = activePossession.copy(
            result = PossessionResult.TURNOVER,
            endTime = if (isLive) System.currentTimeMillis() else activePossession.endTime
        ).apply { turnoverCommittedBy = player.id }

        recordAction(GameAction.Turnover(player.id, false, activePossession.id), isLive)

        if (isLive) {
            if (_currentPossessionType.value == PossessionType.OFFENSE) {
                transitionToDefense(true)
            } else {
                startOffensivePossession(true)
            }
            viewModelScope.launch { _statEventMessage.emit("Turnover by ${player.lastName} recorded.") }
        }
    }

    /** Captures current player IDs on the pitch. */
    private fun capturePlayersOnPitch(): Map<String, String> {
        return _pitchOccupants.value.filterValues { it != null }.mapValues { it.value!!.id }
    }

    /** Captures current player IDs in the penalty box. */
    private fun capturePlayersInBox(): Map<String, String> {
        return _playersInBox.value.filterValues { it != null }.mapValues { it.value!!.id }
    }

    /** Helper to create a new possession object. */
    private fun createNewPossession(type: PossessionType): Possession {
        return Possession(
            id = generatePossessionId(),
            startTime = System.currentTimeMillis(),
            possessionType = type,
            pitchState = if (capturePlayersInBox().isEmpty()) PitchState.FULL_LINE else PitchState.MISSING_PLAYERS,
            playersOnPitch = capturePlayersOnPitch(),
            playersInBox = capturePlayersInBox(),
            result = PossessionResult.PENDING
        )
    }

    /**
     * Helper to update a player across the roster, occupants, and penalty box.
     * This ensures new instances are propagated to trigger StateFlow emissions.
     */
    private fun updatePlayerInState(updatedPlayer: Player) {
        val roster = _currentTeam.value.roster
        val index = roster.indexOfFirst { it.id == updatedPlayer.id }
        if (index != -1) {
            roster[index] = updatedPlayer
        }

        val occupants = _pitchOccupants.value.toMutableMap()
        occupants.entries.find { it.value?.id == updatedPlayer.id }?.let { (key, _) ->
            occupants[key] = updatedPlayer
        }
        _pitchOccupants.value = occupants

        val box = _playersInBox.value.toMutableMap()
        box.entries.find { it.value?.id == updatedPlayer.id }?.let { (key, _) ->
            box[key] = updatedPlayer
        }
        _playersInBox.value = box

        refreshData()
    }

    /** Transitions the game state to defense. */
    private fun transitionToDefense(isLive: Boolean) {
        priorPossession = currentPossession
        _currentPossessionType.value = PossessionType.DEFENSE
        currentPossession = createNewPossession(PossessionType.DEFENSE) // Generates NEW ID

        if (isLive) {
            if (_userAuthLevel.value != AuthType.PRIMARY_COACH) {
                viewModelScope.launch { _statEventMessage.emit("Unauthorized: Only Chaser Captain can change possession.") }
                return
            }
            _uiState.value = if (_beaterStatsEnabled.value) RecordingState.RecordingBludgers else RecordingState.Idle
        }
        refreshData()
    }

    /** Records the number of bludgers held at the start of defense. */
    fun submitDefensiveBludgers(count: Int) {
        currentPossession = currentPossession?.copy(bludgerCount = count)
        _uiState.value = RecordingState.Idle
        viewModelScope.launch { _statEventMessage.emit("Defensive set started with $count bludgers.") }
    }

    /** Forces a UI refresh of players and occupants. */
    private fun refreshData() {
        val updatedRoster = _currentTeam.value.roster.toList()
        _allPlayers.value = updatedRoster

        // Sync Pitch Occupants with the updated roster data
        val updatedOccupants = _pitchOccupants.value.toMutableMap()
        updatedOccupants.forEach { (slot, player) ->
            if (player != null) {
                updatedOccupants[slot] = updatedRoster.find { it.id == player.id }
            }
        }
        _pitchOccupants.value = updatedOccupants

        // Sync Penalty Box with the updated roster data
        val updatedBox = _playersInBox.value.toMutableMap()
        updatedBox.forEach { (slot, player) ->
            if (player != null) {
                updatedBox[slot] = updatedRoster.find { it.id == player.id }
            }
        }
        _playersInBox.value = updatedBox
    }

    /** Transitions the game state to offense. */
    private fun startOffensivePossession(isLive: Boolean) {
        priorPossession = currentPossession
        _currentPossessionType.value = PossessionType.OFFENSE
        currentPossession = createNewPossession(PossessionType.OFFENSE) // Generates NEW ID

        if (isLive && _userAuthLevel.value != AuthType.PRIMARY_COACH) return

        refreshData()
    }

    /** Records a forced turnover and transitions to offense. */
    fun finalizeTurnoverForced(player: Player, isLive: Boolean) {
        val active = currentPossession ?: return
        currentPossession = active.copy(
            result = PossessionResult.TURNOVER,
            endTime = if (isLive) System.currentTimeMillis() else active.endTime
        ).apply { turnoverForcedBy = player.id }

        recordAction(GameAction.Turnover(player.id, true, active.id), isLive)

        if (isLive) {
            startOffensivePossession(true)
            viewModelScope.launch { _statEventMessage.emit("Turnover Forced by ${player.lastName}!") }
        }
    }

    /** Initiates recording a conceded goal. */
    fun initiateConcededGoal(hoop: HoopID) {
        _uiState.value = RecordingState.SelectingConcededGoalType(hoop)
    }

    /** Records a conceded goal and transitions back to offense. */
    fun finalizeConcededGoal(type: ShotType, hoopId: HoopID, isLive: Boolean) {
        val active = currentPossession ?: return

        currentPossession = active.copy(
            result = PossessionResult.CONCEDED_GOAL,
            endTime = if (isLive) System.currentTimeMillis() else active.endTime
        )

        currentPossession?.shots?.add(Shot("OPPONENT", null, type, hoopId, isGood = true))
        _oppTeamScore.value += 10

        if (isLive) {
            recordAction(GameAction.ConcededGoal(type, hoopId, active.id), isLive)
            startOffensivePossession(isLive = true)

            viewModelScope.launch {
                _statEventMessage.emit("Goal Conceded (${type.name}) - Back on Offense!")
            }
        }
    }

    /** Manually switches the possession state. */
    fun switchPossession() {
        val active = currentPossession ?: return
        recordAction(GameAction.Turnover("", false, active.id), isLive = true)
        val completed = active.copy(result = PossessionResult.TURNOVER, endTime = System.currentTimeMillis())
        _currentTeam.value.recordPossession(completed)

        if (_currentPossessionType.value == PossessionType.OFFENSE) transitionToDefense(true)
        else startOffensivePossession(true)

        viewModelScope.launch { _statEventMessage.emit("Possession Swapped: ${_currentPossessionType.value.name}") }
    }

    fun resetGame(isReplay: Boolean) {
        if (!isReplay) {
            togglePitchEnabled(EnableType.NONE)
            _gameID.value = ""
            dbHelper.stopListening()
        }

        _currentTeam.value.roster.forEachIndexed { index, player ->
            _currentTeam.value.roster[index] = player.copy(
                possessions = emptyList(),
                beats = 0,
                isBeatAmount = 0,
                yellowCards = 0,
                blueCards = 0,
                redCards = 0,
                caughtFlag = false
            )
        }

        // 3. UI RESET
        _gameLog.value = emptyList()
        _uiState.value = RecordingState.Idle
        _gamePhase.value = GamePhase.DEFAULT
        _isFlagCaught.value = false
        _playersInBox.value = emptyMap()
        _pitchOccupants.value = emptyMap()
        _currentTeamScore.value = 0
        _oppTeamScore.value = 0
        _currentPossessionType.value = PossessionType.OFFENSE

        refreshData()
        resetRecordingState()
    }

    /** Initializes and starts a new game. */
    fun startGame() {
        resetGame(false)
        togglePitchEnabled(EnableType.GAME_IN_PROGRESS)
        loadRoster()
        _gameID.value = "${_currentTeam.value.teamAcronym.uppercase()}vs${_opposingTeam.value.uppercase()}_${LocalDate.now()}"
        dbHelper.initializeGameOnServer(_currentTeam.value.teamAcronym, _gameID.value, _opposingTeam.value)
        dbHelper.listenToGame(_currentTeam.value.teamAcronym, _gameID.value) { updatedLog ->
            _gameLog.value = updatedLog
            reconstructGameState(updatedLog)
        }
        startOffensivePossession(true)
    }

    /** Sets the UI to show a loaded game. */
    fun loadGame() {
        _pitchEnabled.value = EnableType.GAME_LOADED
    }

    /** Resets the game state. */
    fun stopGame() {
        resetGame(false)
    }

    /** Resets game state and stops database listening. */
    fun exitGame() {
        dbHelper.stopListening()

        _gameID.value = ""
        togglePitchEnabled(EnableType.NONE)

        resetGame(isReplay = false)
    }

    /** Adds a new player to the roster and database. */
    fun addNewPlayerToRoster(name: String, gender: GenderIdentity, number: Int, primaryPos: QuadballPosition, positions: Set<QuadballPosition>): Player {
        val newPlayer = Player.create(
            name = name,
            number = number,
            primaryPosition = primaryPos,
            positions = positions.toMutableSet(),
            gender = gender,
            teamAcronym = "txq",
            existingRoster = _allPlayers.value
        )
        dbHelper.addPlayerToTeam("txq", newPlayer) {
            viewModelScope.launch { _statEventMessage.emit("Added ${newPlayer.name} as ${newPlayer.id}") }
            loadRoster()
        }
        return newPlayer
    }

    /** Updates an existing player's information. */
    fun updatePlayerInfo(player: Player, name: String, gender: GenderIdentity, number: Int, positions: Set<QuadballPosition>, primaryPos: QuadballPosition) {
        val updatedPlayer = player.copy(name = name, gender = gender, number = number, positions = positions, primaryPosition = primaryPos)
        updatePlayerInState(updatedPlayer)
        viewModelScope.launch { _statEventMessage.emit("Updated ${updatedPlayer.lastName}'s info.") }
    }

    /** Toggles the gender rule enforcement. */
    fun toggleGenderRule(isEnabled: Boolean) {
        _isGenderRuleEnabled.value = isEnabled
    }

    /** Checks if a player can be added to the pitch according to the gender rule. */
    private fun canAddPlayerToPitch(newPlayer: Player, targetSlotId: String): Boolean {
        if (!_isGenderRuleEnabled.value) return true
        val currentOccupants = _pitchOccupants.value
        val playerBeingReplaced = currentOccupants[targetSlotId]
        val playersStaying = currentOccupants.values.filterNotNull().filter { it.id != playerBeingReplaced?.id && it.id != newPlayer.id }
        val sameGenderCount = playersStaying.count { it.gender == newPlayer.gender }
        return sameGenderCount < 4
    }

    /** Toggles the position rule enforcement. */
    fun togglePositionRule(isEnabled: Boolean) {
        _isPositionRuleEnabled.value = isEnabled
    }

    /** Toggles whether beater-specific stats are tracked. */
    fun toggleBeaterStatsEnabled(isEnabled: Boolean) {
        _beaterStatsEnabled.value = isEnabled
    }

    /** Generates a unique ID for a new possession. */
    private fun generatePossessionId(): String {
        possessionCounter++
        val team1 = _currentTeam.value.teamAcronym.uppercase()
        val team2 = _opposingTeam.value.uppercase()
        return "${team1}vs${team2}_$possessionCounter"
    }

    /** Records a game action to the log and optionally the database. */
    private fun recordAction(action: GameAction, isLive: Boolean = true) {
        _gameLog.value = _gameLog.value + action
        if (isLive) dbHelper.submitAction(_currentTeam.value.teamAcronym, _gameID.value, action)
    }

    /** Sets the currently authenticated user. */
    fun setCurrentAuthUser(user: User) {
        _currentAuthUser.value = user
    }

    /** Gets the currently authenticated user. */
    fun getCurrentAuthUser(): User {
        return _currentAuthUser.value
    }

    /** Finalizes the game on the server and resets local state. */
    fun finalizeGame() {
        val currentRoster = _currentTeam.value?.roster ?: return
        val gameData = hashMapOf(
            "opponent" to _opposingTeam.value,
            "finalScore" to "${_currentTeamScore.value ?: 0} - ${_oppTeamScore.value}",
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        dbHelper.finalizeGame(_gameID.value, currentTeam.value.teamAcronym, gameData, currentRoster) {
            Log.d("VIEW_MODEL", "Game finalized and UI can be reset.")
        }
        resetRecordingState()
        _pitchEnabled.value = EnableType.NONE
        _pitchOccupants.value = emptyMap()
        _playersInBox.value = emptyMap()
    }

    /** Reverts the last recorded action and updates state accordingly. */
    fun undoLastAction() {
        val log = _gameLog.value
        if (log.isEmpty()) return
        val lastAction = log.last()
        var dropSecond = false
        var activePossession = currentPossession ?: return

        /** Helper to revert to the previous possession state. */
        fun revertPossession() {
            priorPossession?.let {
                currentTeam.value.deletePossession(it)
                activePossession = it
            }
            priorPossessionType?.let { _currentPossessionType.value = it }
        }

        when (lastAction) {
            is GameAction.MissedShot -> activePossession.shots.removeAt(activePossession.shots.lastIndex)
            is GameAction.Goal -> {
                revertPossession()
                activePossession.shots.removeAt(activePossession.shots.lastIndex)
                activePossession = activePossession.copy(result = PossessionResult.PENDING)
                _currentTeamScore.value = (_currentTeamScore.value - 10).coerceAtLeast(0)
            }

            is GameAction.Turnover -> {
                revertPossession()
                activePossession = activePossession.copy(result = PossessionResult.PENDING)
                activePossession.turnoverForcedBy = null
                activePossession.turnoverCommittedBy = null
            }

            is GameAction.ConcededGoal -> {
                revertPossession()
                activePossession.shots.removeAt(activePossession.shots.lastIndex)
                activePossession = activePossession.copy(result = PossessionResult.PENDING)
                _oppTeamScore.value = (_oppTeamScore.value - 10).coerceAtLeast(0)
            }

            is GameAction.Beat -> {
                _allPlayers.value.find { it.id == lastAction.playerId }?.let { player ->
                    val updatedPlayer = player.copy(beats = (player.beats - 1).coerceAtLeast(0))
                    updatePlayerInState(updatedPlayer)
                }
            }

            is GameAction.FlagCaught -> {
                if (lastAction.playerId == "OPPONENT") _oppTeamScore.value = (_oppTeamScore.value - 35).coerceAtLeast(0)
                else {
                    _allPlayers.value.find { it.id == lastAction.playerId }?.let { player ->
                         val updatedPlayer = player.copy(caughtFlag = false)
                         updatePlayerInState(updatedPlayer)
                    }
                    _currentTeamScore.value = (_currentTeamScore.value - 35).coerceAtLeast(0)
                }
                _isFlagCaught.value = false
                _gamePhase.value = GamePhase.SEEKER_FLOOR
            }

            is GameAction.Penalty -> {
                _allPlayers.value.find { it.id == lastAction.playerId }?.let { player ->
                    val updatedPlayer = when (lastAction.cardType) {
                        CardType.BLUE -> player.copy(blueCards = (player.blueCards - 1).coerceAtLeast(0))
                        CardType.YELLOW -> player.copy(yellowCards = (player.yellowCards - 1).coerceAtLeast(0))
                        else -> player
                    }
                    updatePlayerInState(updatedPlayer)
                }
                _playersInBox.value.entries.find { it.value?.id == lastAction.playerId }?.key?.let { key ->
                    _playersInBox.value = _playersInBox.value - key
                }
            }

            is GameAction.PenaltyEnded -> {
                val player = _allPlayers.value.find { it.id == lastAction.playerId }
                _pitchOccupants.value.entries.find { it.value?.id == lastAction.playerId }?.key?.let { slotId ->
                    _playersInBox.value = _playersInBox.value + (slotId to player)
                }
            }

            is GameAction.Substitution -> {
                if (log.size >= 2) {
                    val secondToLastAction = log[log.size - 2]
                    if (secondToLastAction is GameAction.Penalty && secondToLastAction.cardType == CardType.RED) {
                        _allPlayers.value.find { it.id == secondToLastAction.playerId }?.let { player ->
                            val updatedPlayer = player.copy(redCards = (player.redCards - 1).coerceAtLeast(0))
                            updatePlayerInState(updatedPlayer)
                            assignPlayerToSlot(updatedPlayer, secondToLastAction.slotId, true)
                        }
                        _playersInBox.value.entries.find { it.value?.id == secondToLastAction.playerId }?.key?.let { key ->
                            _playersInBox.value = _playersInBox.value - key
                        }
                        dropSecond = true
                    }
                }
                val inPlayer = _allPlayers.value.find { it.id == lastAction.inPlayerId }
                val outPlayer = _allPlayers.value.find { it.id == lastAction.outPlayerId }
                if (outPlayer == null && inPlayer != null) removePlayerFromField(inPlayer, true)
                else if (outPlayer != null) assignPlayerToSlot(outPlayer, lastAction.slot, true)
            }
        }
        currentPossession = activePossession
        _gameLog.value = if (dropSecond) log.dropLast(2) else log.dropLast(1)
        viewModelScope.launch { _statEventMessage.emit("Undid: ${lastAction.javaClass.simpleName}") }
    }

    /** Enables or disables the pitch UI. */
    fun togglePitchEnabled(enableType: EnableType) {
        _pitchEnabled.value = enableType
    }

    /** Joins an existing live game from the server. */
    fun joinLiveGame(gameId: String) {
        dbHelper.checkGameJoinable(_currentTeam.value.teamAcronym, gameId) { errorMessage ->
            if (errorMessage != null) {
                viewModelScope.launch { _statEventMessage.emit(errorMessage) }
                _pitchEnabled.value = EnableType.NONE
            } else {
                _gameID.value = gameId

                dbHelper.fetchGameMetadata(currentTeam.value.teamAcronym, gameId) { metadata ->
                    _opposingTeam.value = metadata["opponent"] as? String ?: "Unknown Opponent"
                }

                dbHelper.listenToGame(currentTeam.value.teamAcronym, gameId) { updatedLog ->
                    if (updatedLog.isEmpty()) {
                        viewModelScope.launch { _statEventMessage.emit("Invalid Game Data") }
                        resetGame(false)
                    } else {
                        reconstructGameState(updatedLog)
                        togglePitchEnabled(EnableType.GAME_IN_PROGRESS)
                    }
                }
            }
        }
    }

    /** Reconstructs the full game state from a list of actions. */
    private fun reconstructGameState(actions: List<GameAction>) {
        resetGame(isReplay = true)

        // 1. Group by ID and sort numerically (ensures _10 follows _9)
        val possessionsMap = actions
            .filter { it.possessionId != null }
            .groupBy { it.possessionId }
            .toSortedMap(compareBy { it?.substringAfterLast('_')?.toIntOrNull() ?: 0 })

        possessionsMap.forEach { (pId, actionsInPossession) ->
            val chronologicalActions = actionsInPossession.sortedBy { it.timestamp }

            currentPossession = Possession(
                id = pId ?: generatePossessionId(),
                possessionType = _currentPossessionType.value,
                playersOnPitch = capturePlayersOnPitch(),
                playersInBox = capturePlayersInBox(),
                result = PossessionResult.PENDING
            )

            chronologicalActions.forEach { action -> replayAction(action) }

            currentPossession?.let { finished ->
                _currentTeam.value.recordPossession(finished)

                val last = chronologicalActions.lastOrNull()
                when (last) {
                    is GameAction.Goal -> _currentPossessionType.value = PossessionType.DEFENSE
                    is GameAction.ConcededGoal -> _currentPossessionType.value = PossessionType.OFFENSE
                    is GameAction.Turnover -> {
                        if (_currentPossessionType.value == PossessionType.OFFENSE) {
                            _currentPossessionType.value = PossessionType.DEFENSE
                        } else {
                            _currentPossessionType.value = PossessionType.OFFENSE
                        }
                    }
                    else -> {}
                }
            }
        }

        possessionCounter = possessionsMap.keys.count()

        val lastResult = currentPossession?.result
        if (lastResult != PossessionResult.PENDING) {
            currentPossession = createNewPossession(_currentPossessionType.value)
        }

        refreshData()
    }

    /** Replays a single action to update the local state during reconstruction. */
    private fun replayAction(action: GameAction) {
        val players = _allPlayers.value
        when (action) {
            is GameAction.Substitution -> {
                players.find { it.id == action.inPlayerId }?.let { assignPlayerToSlot(it, action.slot, false) }
                    ?: players.find { it.id == action.outPlayerId }?.let { removePlayerFromField(it, false) }
            }

            is GameAction.MissedShot -> {
                players.find { it.id == action.playerId }?.let { recordMissedShot(it, action.hoop, action.type, false) }
            }

            is GameAction.Goal -> {
                players.find { it.id == action.playerId }?.let { shooter ->
                    saveGoal(shooter, players.find { it.id == action.assistId }, action.hoop, action.type, false)
                }
            }

            is GameAction.Beat -> {
                players.find { it.id == action.playerId }?.let { finalizeBeat(it, action.isOpponentBeat, false) }
            }

            is GameAction.Penalty -> {
                players.find { it.id == action.playerId }?.let { finalizePenalty(it, action.cardType, action.slotId, false) }
            }

            is GameAction.PenaltyEnded -> {
                val actualSlot = _playersInBox.value.entries.find { it.value?.id == action.playerId }?.key

                if (actualSlot != null) {
                    finalizePenaltyEnded(actualSlot, false)
                } else {
                    // Fallback: If we can't find them in a slot, just clear the whole map if it's messy
                    Log.e("SYNC_ERROR", "Could not find player ${action.playerId} in penalty box during sync")
                }
            }

            is GameAction.ConcededGoal -> {
                finalizeConcededGoal(action.type, action.hoop,false) // Pass isLive = false
            }

            is GameAction.Turnover -> {
                val player = players.find { it.id == action.playerId }
                if (player != null) {
                    if (action.isForced) finalizeTurnoverForced(player, false)
                    else finalizeTurnover(player, false)
                } else {
                    currentPossession = currentPossession?.copy(result = PossessionResult.TURNOVER)
                }
            }

            is GameAction.FlagCaught -> {
                players.find { it.id == action.playerId }?.let { finalizeFlagCatch(it, false) } ?: finalizeOpponentFlagCatch(false)
            }
        }
    }
}

/** Factory for creating instances of MainViewModel with an AuthUser. */
class MainViewModelFactory(private val authUser: AuthUser) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(authUser) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}