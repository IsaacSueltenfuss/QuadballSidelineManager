package com.example.quadballsidelinemanager

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.example.quadballsidelinemanager.models.HoopID
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

// Indicates the progress in recording a goal.
sealed class RecordingState {
    object Idle : RecordingState()
    data class SelectingAction(val player: Player) : RecordingState()
    data class SelectingShotResult(val player: Player) : RecordingState()
    data class SelectingHoop(val player: Player, val isGood: Boolean) : RecordingState()
    data class SelectingShotType(val player: Player, val hoopId: HoopID, val isGood: Boolean) : RecordingState()
    data class SelectingAssistantDecision(val player: Player, val hoop: HoopID, val type: ShotType) : RecordingState()
    data class SelectingAssistant(val player: Player, val hoop: HoopID, val type: ShotType) : RecordingState()

    data class SelectingConcededGoalType(val hoopId: HoopID) : RecordingState()
}

// Different sorting possibilities
enum class SortType { NUMBER, NAME, POSITION, GENDER, POSSESSIONS, PLUS_MINUS }

// Different game phases
enum class GamePhase { FIRST_HALF, SECOND_HALF, TIMEOUT }

enum class EnableType { GAME_IN_PROGRESS, GAME_LOADED, NONE}

class MainViewModel : ViewModel() {

    // Opposing team name (for use in export CSV)
    private val _opposingTeam = MutableStateFlow<String>("")
    val opposingTeam: StateFlow<String> = _opposingTeam.asStateFlow()

    // Whether game interaction is allowed
    private val _pitchEnabled = MutableStateFlow<EnableType>(EnableType.NONE)
    val pitchEnabled: StateFlow<EnableType> = _pitchEnabled.asStateFlow()

    // Current game phase (determines whether slot_seeker is displayed in PitchFragment)
    private val _gamePhase = MutableStateFlow<GamePhase>(GamePhase.FIRST_HALF)
    val gamePhase: StateFlow<GamePhase> = _gamePhase.asStateFlow()

    // Position Filter (TODO only allows for one position at a time currently)
    private val _positionFilter = MutableStateFlow<QuadballPosition?>(null)
    val positionFilter: StateFlow<QuadballPosition?> = _positionFilter.asStateFlow()

    // Active Filter
    private val _onlyActiveFilter = MutableStateFlow(false)
    val onlyActiveFilter: StateFlow<Boolean> = _onlyActiveFilter.asStateFlow()

    // Playing Filter
    private val _onlyPlayingFilter = MutableStateFlow(false)
    val onlyPlayingFilter: StateFlow<Boolean> = _onlyPlayingFilter.asStateFlow()

    // Sort Filter
    private val _sortFilter = MutableStateFlow(SortType.POSITION)
    val sortFilter: StateFlow<SortType> = _sortFilter.asStateFlow()

    // All players (master list, regardless of active/playing)
    private val _allPlayers = MutableStateFlow<List<Player>>(emptyList())
    val allPlayers: StateFlow<List<Player>> = _allPlayers.asStateFlow()

    // All players on pitch currently (playing only)
    private val _pitchOccupants = MutableStateFlow<Map<String, Player?>>(emptyMap())
    val pitchOccupants: StateFlow<Map<String, Player?>> = _pitchOccupants.asStateFlow()

    // All players to be displayed in the RosterFragment (regardless of playing)
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

    // All players displayed in the bench in the PitchFragment (does not include active)
    val benchPlayers = combine(
        _allPlayers,
        _pitchOccupants,
        _positionFilter,
        _onlyPlayingFilter,
        _sortFilter
    ) { all, occupants, pos, onlyPlaying, sort ->
        val onPitchIds = occupants.values.filterNotNull().map { it.id }.toSet()

        // Filters master list for active players who are not playing (if filter not checked)
        // and are in the position filter
        val filteredList = all.filter { player ->
            val statusMatches = if (onlyPlaying) {
                onPitchIds.contains(player.id)
            } else {
                !onPitchIds.contains(player.id)
            }

            val positionMatches = pos == null || pos in player.positions

            player.isActive && statusMatches && positionMatches
        }
        // Sorts this updated list by the sortFilter
        filteredList.sortedWith(getComparator(sort))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Indicates whether the benchContainer is expanded or hidden in PitchFragment
    private val _isBenchExpanded = MutableStateFlow(true)
    val isBenchExpanded: StateFlow<Boolean> = _isBenchExpanded.asStateFlow()

    // Indicates a slot that has been clicked (and has started the sub process)
    private val _pendingSlot = MutableStateFlow<String?>(null)
    val pendingSlot: StateFlow<String?> = _pendingSlot.asStateFlow()

    // Indicates a player that has been clicked (and has started the sub process)
    private val _pendingPlayer = MutableStateFlow<Player?>(null)
    val pendingPlayer: StateFlow<Player?> = _pendingPlayer.asStateFlow()

    // Indicates progress in recording a goal
    private val _uiState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val uiState = _uiState.asStateFlow()

    // Current team (allows possession submission)
    private val _currentTeam = MutableStateFlow<Team>(Team("New Team", "TBD"))
    val currentTeam = _currentTeam.asStateFlow()

    private val _statEventMessage = MutableSharedFlow<String>()
    val statEventMessage: SharedFlow<String> = _statEventMessage

    private var currentPossession: Possession? = null

    private val _currentPossessionType = MutableStateFlow(PossessionType.OFFENSE)
    val currentPossessionType: StateFlow<PossessionType> = _currentPossessionType.asStateFlow()

    init {
        loadRoster()
    }

    private fun loadRoster() {
        _currentTeam.value = Team(teamName = "Texas", teamAcronym = "txq")

        _currentTeam.value.addNewPlayer("Nico Salinas", 11, mutableSetOf(QuadballPosition.KEEPER,
            QuadballPosition.CHASER), GenderIdentity.MALE, R.drawable.headshot_nico)
        _currentTeam.value.addNewPlayer("Tyler Shearin", 36, mutableSetOf(QuadballPosition.BEATER), GenderIdentity.MALE, R.drawable.headshot_tyler)
        _currentTeam.value.addNewPlayer("Arturo Juarez", 12, mutableSetOf(QuadballPosition.BEATER), GenderIdentity.MALE, R.drawable.headshot_arturo)
        _currentTeam.value.addNewPlayer("Grayson Floyd", 99, mutableSetOf(QuadballPosition.SEEKER), GenderIdentity.MALE, R.drawable.headshot_grayson)
        _currentTeam.value.addNewPlayer("Isaac Sueltenfuss", 71, mutableSetOf(QuadballPosition.CHASER), GenderIdentity.MALE, R.drawable.headshot_isaac)
        _currentTeam.value.addNewPlayer("Addison Hewitt", 14, mutableSetOf(QuadballPosition.CHASER), GenderIdentity.FEMALE, R.drawable.headshot_addison)
        _currentTeam.value.addNewPlayer("Yashna Singhania", 16, mutableSetOf(QuadballPosition.CHASER), GenderIdentity.FEMALE, R.drawable.headshot_yashna)
        _currentTeam.value.addNewPlayer("Adelynn Harris", 98, mutableSetOf(QuadballPosition.CHASER), GenderIdentity.FEMALE)
        _currentTeam.value.addNewPlayer("Peter Mosqueda", 54, mutableSetOf(QuadballPosition.KEEPER), GenderIdentity.MALE, R.drawable.headshot_peter)
        _currentTeam.value.addNewPlayer("Phineas Roberts", 27, mutableSetOf(QuadballPosition.KEEPER), GenderIdentity.MALE)
        _currentTeam.value.addNewPlayer("Daniel Dao", 93, mutableSetOf(QuadballPosition.KEEPER), GenderIdentity.MALE, R.drawable.headshot_daniel)
        _currentTeam.value.addNewPlayer("Christina Milne", 1, mutableSetOf(QuadballPosition.BEATER), GenderIdentity.FEMALE, R.drawable.headshot_christina_milne)

        val activePlayers = listOf<String>("txqNS", "txqIS", "txqAH", "txqDD", "txqTS", "txqCM")
        val shot = listOf<Shot>(Shot("txqIS", "txqNS", ShotType.SHOT, hoopID = HoopID.TALL, isGood = true)).toMutableList()
        val possession = Possession("TXQ vs. SHSU 001", possessionType = PossessionType.OFFENSE, playersOnPitch = activePlayers, playersInBox = emptyList(), result = PossessionResult.GOAL, shots = shot)
        _currentTeam.value.recordPossession(possession)

        _allPlayers.value = _currentTeam.value.roster.toList()
        Log.d("DEBUG", "ViewModel Initialized: Loaded ${_allPlayers.value.size} players")
    }

    fun getRequiredPositionForSlot(slotId: String): QuadballPosition {
        return when {
            slotId.contains("keeper", ignoreCase = true) -> QuadballPosition.KEEPER
            slotId.contains("chaser", ignoreCase = true) -> QuadballPosition.CHASER
            slotId.contains("beater", ignoreCase = true) -> QuadballPosition.BEATER
            slotId.contains("seeker", ignoreCase = true) -> QuadballPosition.SEEKER
            else -> QuadballPosition.UTILITY // Fallback
        }
    }

    /**
     * Toggles the active status for a player
     */
    fun toggleActivePlayerStatus(player: Player) {
        val updatedList = _allPlayers.value.map {
            if (it.id == player.id) {
                // Toggles the active status
                it.copy(isActive = !it.isActive)
            } else it
        }
        _allPlayers.value = updatedList

        // If a player has been marked inactive but is also on the pitch, take them off the pitch and back on the bench
        if (!player.isActive) {
            val currentOccupants = _pitchOccupants.value.toMutableMap()
            val slot = currentOccupants.entries.find { it.value?.id == player.id }?.key
            if (slot != null) {
                currentOccupants[slot] = null
                _pitchOccupants.value = currentOccupants
            }
        }
    }

    /**
     * Creates a sorting comparator based on SortType
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
     * Returns a priority for each position to sort them in order
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

    /**
     * Updates the sort filter
     */
    fun updateSort(type: SortType) { _sortFilter.value = type }

    /**
     * Updates the position filter
     */
    fun setPositionFilter(pos: QuadballPosition?) { _positionFilter.value = pos }

    /**
     * Toggles the active filter
     */
    fun toggleOnlyActiveFilter() { _onlyActiveFilter.value = !_onlyActiveFilter.value }

    /**
     * Toggles the visibility of the bench
     */
    fun toggleBenchVisibility() {
        _isBenchExpanded.value = !_isBenchExpanded.value
    }

    /**
     * Heart of the substitution process. Assigns a (player, slot) pairing.
     */
    fun assignPlayerToSlot(player: Player, targetSlotId: String) {
        val requiredPos = getRequiredPositionForSlot(targetSlotId)

        // Check if player is eligible for this specific slot
        if (!player.positions.contains(requiredPos)) {
            _statEventMessage.tryEmit("${player.lastName} is not registered as a ${requiredPos.name}")
            resetRecordingState() // Reset the drag/sub process
            return
        }

        // Gets all current players on pitch
        val currentMap = _pitchOccupants.value.toMutableMap()

        // Finds the slot where the player is currently (if applicable)
        val existingSlot = currentMap.entries.find { it.value?.id == player.id }?.key
        // If the player is in another slot currently take them out of that slot
        if (existingSlot != null) {
            currentMap[existingSlot] = null
        }

        // Assigns the player to the target slot (TODO do I need to check if the targetSlot is null)
        currentMap[targetSlotId] = player
        _pitchOccupants.value = currentMap

        // Clears pending substitution variables
        clearPendingStates()

        // Gets the total number of players on the field. If there are still open slots keep the bench open
        val totalOnField = currentMap.values.filterNotNull().size
        val maxPlayers = if (gamePhase.value == GamePhase.SECOND_HALF) 7 else 6
        _isBenchExpanded.value = if (totalOnField < maxPlayers) true else false
    }

    /**
     * Clears the pending substitution variables. This is the only location this occurs.
     */
    fun clearPendingStates() {
        Log.d("VM_LIFECYCLE", "CLEARING pendingPlayer! StackTrace: ${Log.getStackTraceString(Throwable())}")
        _pendingPlayer.value = null
        _pendingSlot.value = null

        _positionFilter.value = null
    }

    /**
     * Starts the substitution process by clicking on a player. If they are dragging the player keep the bench open, else close it.
     */
    fun startSubProcessPlayer(player: Player, isDragging: Boolean) {
        Log.d("VM_LIFECYCLE", "SETTING pendingPlayer: ${player.lastName}")
        _pendingPlayer.value = player
        _pendingSlot.value = null

        Log.d("VM_LIFECYCLE", "Current _pendingPlayer value: ${_pendingPlayer.value?.lastName}")

        if (!isDragging) {
            _isBenchExpanded.value = false
        }
    }

    /**
     * Starts the substitution process by clicking on an empty slot. Opens the bench.
     */
    fun startSubProcessSlot(slotId: String, requiredPos: QuadballPosition) {
        _pendingSlot.value = slotId
        _pendingPlayer.value = null

        _positionFilter.value = requiredPos

        _isBenchExpanded.value = true

        Log.d("TACTICAL", "Targeting slot: $slotId. Opening bench.")
    }

    /**
     * Swaps two players on the pitch.
     */
    fun swapPlayers(sourceSlotId: String, targetSlotId: String) {
        val currentMap = _pitchOccupants.value.toMutableMap()

        // Get the players in both positions
        val sourcePlayer = currentMap[sourceSlotId]
        val targetPlayer = currentMap[targetSlotId]

        // Perform the swap
        currentMap[targetSlotId] = sourcePlayer
        currentMap[sourceSlotId] = targetPlayer

        _pitchOccupants.value = currentMap
        clearPendingStates()
    }

    /**
     * Updates the opposing team name
     */
    fun updateOpposingTeam(team: String) {
        _opposingTeam.value = team
    }

    /**
     * Updates the current game phase
     */
    fun updateGamePhase(phase: GamePhase) {
        _gamePhase.value = phase
    }

    /**
     * Toggles the playing filter
     */
    fun toggleOnlyPlayingFilter() {
        _onlyPlayingFilter.value = !_onlyPlayingFilter.value
    }

    fun toggleStartingSide() {
        _currentPossessionType.value = when (_currentPossessionType.value) {
            PossessionType.OFFENSE -> PossessionType.DEFENSE
            PossessionType.DEFENSE -> PossessionType.OFFENSE
            else -> PossessionType.OFFENSE
        }
    }

    /**
     * Resets the UI State for action attribution
     */
    fun resetRecordingState() {
        _uiState.value = RecordingState.Idle
        _pendingSlot.value = null
        _pendingPlayer.value = null
        _positionFilter.value = null

        viewModelScope.launch {
            _statEventMessage.emit("Data entry was canceled.")
        }
    }

    /**
     * Initiates the action attribution process for a player
     */
    fun initiateAction(player: Player) {
        _uiState.value = RecordingState.SelectingAction(player)
    }

    /**
     * Second stage in action attribution process, selects which action
     */
    fun selectAction(action: String) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingAction) {
            when (action) {
                "Shot" -> _uiState.value = RecordingState.SelectingShotResult(currentState.player)
                "Turnover" -> finalizeTurnover(currentState.player)
                "Turnover Forced" -> finalizeTurnoverForced(currentState.player)
                else -> _uiState.value = RecordingState.Idle
            }
        }
    }

    fun setShotResult(isGood: Boolean) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingShotResult) {
            _uiState.value = RecordingState.SelectingHoop(currentState.player, isGood)
        }
    }

    /**
     * Select the hoop scored on after a goal
     */
    fun selectHoop(hoopId: HoopID) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingHoop) {
            _uiState.value = RecordingState.SelectingShotType(currentState.player, hoopId, currentState.isGood)
        }
    }

    /**
     * Final step of goal attribution process
     */
    fun selectShotType(type: ShotType) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingShotType) {
            if (currentState.isGood) {
                // "Good" shot proceeds to Assist selection
                _uiState.value = RecordingState.SelectingAssistantDecision(
                    currentState.player, currentState.hoopId, type
                )
            } else {
                // "Missed" shot records and resets to Idle without ending possession
                recordMissedShot(currentState.player, currentState.hoopId, type)
                _uiState.value = RecordingState.Idle
            }
        }
    }

    private fun recordMissedShot(player: Player, hoop: HoopID, type: ShotType) {
        val shot = Shot(player.id, null, type, hoop, isGood = false)
        getOrStartPossession().shots.add(shot)

        viewModelScope.launch {
            _statEventMessage.emit("Missed ${type.name} by ${player.lastName} recorded.")
        }
    }

    private fun getOrStartPossession(): Possession {
        if (currentPossession == null) {
            // Capture the exact players on the pitch when the possession began
            val activePlayerIds = _pitchOccupants.value.values.filterNotNull().map { it.id }

            currentPossession = Possession(
                id = "POS_${System.currentTimeMillis()}",
                possessionType = PossessionType.OFFENSE,
                playersOnPitch = activePlayerIds,
                playersInBox = emptyList(), // Expand this later if you track cards
                result = PossessionResult.TURNOVER, // Default until proven otherwise
                shots = mutableListOf()
            )
        }
        return currentPossession!!
    }

    fun setAssistantRequired(required: Boolean) {
        val currentState = _uiState.value
        if (currentState is RecordingState.SelectingAssistantDecision) {
            if (required) {
                _uiState.value = RecordingState.SelectingAssistant(
                    currentState.player, currentState.hoop, currentState.type
                )
            } else {
                completeGoal(null) // Directly save with no assistant
            }
        }
    }

    fun completeGoal(assistant: Player?) {
        val currentState = _uiState.value

        when (currentState) {
            is RecordingState.SelectingAssistant -> {
                saveGoal(currentState.player, assistant, currentState.hoop, currentState.type)
                _uiState.value = RecordingState.Idle
            }
            is RecordingState.SelectingAssistantDecision -> {
                // This handles the "No Assistant" path
                saveGoal(currentState.player, assistant, currentState.hoop, currentState.type)
                _uiState.value = RecordingState.Idle
            }
            else -> {
                Log.e("ViewModel", "completeGoal called from unexpected state: $currentState")
            }
        }
    }

    fun saveGoal(player: Player, assistant: Player?, hoop: HoopID, type: ShotType) {
        val activePossession = getOrStartPossession()

        val goalShot = Shot(
            shooterID = player.id,
            assistantID = assistant?.id,
            shotType = type,
            hoopID = hoop,
            isGood = true
        )

        activePossession.shots.add(goalShot)

        val completedPossession = activePossession.copy(result = PossessionResult.GOAL)

        val assistantName = assistant?.lastName ?: "None"
        val message = "GOAL: ${player.lastName} (Assist: $assistantName) - ${type.name} on ${hoop.name} hoop"

        viewModelScope.launch {
            _statEventMessage.emit(message)
        }

        _currentTeam.value.recordPossession(completedPossession)
        currentPossession = null
        _currentPossessionType.value = PossessionType.DEFENSE

        _allPlayers.value = _currentTeam.value.roster.toList()
        _pitchOccupants.value = _pitchOccupants.value.toMutableMap()

        Log.d("STATS_SAVE", "GOAL RECORDED: ${player.lastName} | $hoop | $type")
    }

    fun finalizeTurnover(player: Player) {
        // 1. Retrieve the ongoing possession (or start one if this was a "one-touch" turnover)
        val activePossession = getOrStartPossession()

        // 2. Mark the result and the culprit
        // We copy the object to ensure a new reference for StateFlow reactivity
        val completedPossession = activePossession.copy(
            result = PossessionResult.TURNOVER
        ).apply {
            turnoverCommittedBy = player.id //
        }

        // 3. Commit the completed possession to the Team model
        _currentTeam.value.recordPossession(completedPossession) //
        _currentPossessionType.value = PossessionType.DEFENSE

        // 4. IMPORTANT: Reset the persistent possession to null
        currentPossession = null

        // 5. Trigger UI updates by pushing a fresh list and map
        _allPlayers.value = _currentTeam.value.roster.toList() //
        _pitchOccupants.value = _pitchOccupants.value.toMutableMap() //

        // 6. Provide feedback to the coach and reset the UI wizard
        viewModelScope.launch {
            _statEventMessage.emit("Turnover by ${player.lastName} recorded.") //
        }
        _uiState.value = RecordingState.Idle //
    }

    fun finalizeTurnoverForced(player: Player) {
        // 1. Capture current lineup as the defensive unit
        val activePlayerIds = _pitchOccupants.value.values.filterNotNull().map { it.id } //

        // 2. Create a defensive possession record
        val defensivePossession = Possession(
            id = "DEF_${System.currentTimeMillis()}",
            possessionType = PossessionType.DEFENSE, // CRITICAL for Player.kt filtering
            playersOnPitch = activePlayerIds,
            playersInBox = emptyList(),
            result = PossessionResult.TURNOVER, //
            turnoverForcedBy = player.id // Attributes the stat
        )

        // 3. Commit the record
        _currentTeam.value.recordPossession(defensivePossession) //
        _currentPossessionType.value = PossessionType.OFFENSE

        // 4. Reset UI and trigger reactivity
        _allPlayers.value = _currentTeam.value.roster.toList() //
        _pitchOccupants.value = _pitchOccupants.value.toMutableMap() //

        viewModelScope.launch {
            _statEventMessage.emit("Turnover Forced by ${player.lastName}!") //
        }
        _uiState.value = RecordingState.Idle //
    }

    fun initiateConcededGoal(hoop: HoopID) {
        _uiState.value = RecordingState.SelectingConcededGoalType(hoop)
    }

    fun finalizeConcededGoal(type: ShotType) {
        val state = _uiState.value
        if (state is RecordingState.SelectingConcededGoalType) {
            val activePlayerIds = _pitchOccupants.value.values.filterNotNull().map { it.id }

            val shot = Shot(
                shooterID = "OPPONENT", // Placeholder for stats logic
                assistantID = null,
                shotType = type,
                hoopID = state.hoopId,
                isGood = true
            )

            val concededPossession = Possession(
                id = "CONCEDED_${System.currentTimeMillis()}",
                possessionType = PossessionType.DEFENSE, // Filters for defensive stats
                playersOnPitch = activePlayerIds,
                playersInBox = emptyList(),
                result = PossessionResult.CONCEDED_GOAL,
                shots = mutableListOf(shot)
            )

            // Record the possession to update player +/- and conceded goal stats
            _currentTeam.value.recordPossession(concededPossession)

            // Reset state and flip to Offense
            currentPossession = null
            _currentPossessionType.value = PossessionType.OFFENSE
            _uiState.value = RecordingState.Idle

            // Refresh UI
            _allPlayers.value = _currentTeam.value.roster.toList()
            _pitchOccupants.value = _pitchOccupants.value.toMutableMap()

            viewModelScope.launch {
                _statEventMessage.emit("Goal Conceded (${type.name}) - Back on Offense!")
            }
        }
    }

    fun switchPossession() {
        // 1. Reset any UI recording that might be half-finished
        resetRecordingState()

        // 2. Toggle the possession
        val newType = if (_currentPossessionType.value == PossessionType.OFFENSE) {
            PossessionType.DEFENSE
        } else {
            PossessionType.OFFENSE
        }

        _currentPossessionType.value = newType

        // 3. Optional: Send a toast message so the user knows it worked

        viewModelScope.launch {
            _statEventMessage.emit("Possession: ${newType.name}")
        }
    }

    fun startGame() {
        _pitchEnabled.value = EnableType.GAME_IN_PROGRESS
    }

    fun loadGame() {
        _pitchEnabled.value = EnableType.GAME_LOADED
    }

    fun stopGame() {
        _pitchEnabled.value = EnableType.NONE
    }

    fun addNewPlayerToRoster(name: String, gender: GenderIdentity, number: Int, positions: Set<QuadballPosition>) {
        // 1. Add to the Team model
        _currentTeam.value.addNewPlayer(
            name = name,
            number = number,
            positions = positions.toMutableSet(),
            gender = gender
        )

        // 2. Refresh the Flow so the RosterFragment sees the update
        _allPlayers.value = _currentTeam.value.roster.toList()

        viewModelScope.launch {
            _statEventMessage.emit("Added $name to the roster.")
        }
    }

    fun updatePlayerInfo(
        player: Player,
        name: String,
        gender: GenderIdentity,
        number: Int,
        positions: Set<QuadballPosition>,
        primaryPos: QuadballPosition
    ) {
        val updatedPlayer = player.copy(
            name = name,
            gender = gender,
            number = number,
            positions = positions,
            primaryPosition = primaryPos
        )

        // 2. Update the master StateFlow list with the NEW reference
        _allPlayers.value = _allPlayers.value.map {
            if (it.id == player.id) updatedPlayer else it
        }

        // 3. Update the Team model roster by replacing the item, NOT mutating fields
        val roster = _currentTeam.value.roster
        val index = roster.indexOfFirst { it.id == player.id }
        if (index != -1) {
            roster[index] = updatedPlayer // Replace the whole object
        }

        viewModelScope.launch {
            _statEventMessage.emit("Updated ${player.lastName}'s info.")
        }
    }
}