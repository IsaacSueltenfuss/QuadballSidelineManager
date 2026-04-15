package com.example.quadballsidelinemanager.features.pitch

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.databinding.FragmentPitchBinding
import com.example.quadballsidelinemanager.databinding.ItemFieldSlotBinding
import com.example.quadballsidelinemanager.features.roster.RosterFragment
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.MainViewModel
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import com.example.quadballsidelinemanager.GamePhase
import android.widget.FrameLayout
import androidx.lifecycle.repeatOnLifecycle
import com.example.quadballsidelinemanager.RecordingState
import com.example.quadballsidelinemanager.models.HoopID
import com.example.quadballsidelinemanager.models.PossessionType
import com.example.quadballsidelinemanager.models.QuadballPosition
import com.example.quadballsidelinemanager.models.ShotType

class PitchFragment : Fragment() {

    private var _binding: FragmentPitchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    // Creates a Drag Listener object to be passed into each field slot
    private val slotDragListener = View.OnDragListener { v, event ->
        val targetSlotId = resources.getResourceEntryName(v.id)

        when (event.action) {
            // Start of drag and drop operation
            DragEvent.ACTION_DRAG_STARTED -> {
                Log.d("DRAG_DEBUG", "DRAG STARTED - VM Pending Player is: ${viewModel.pendingPlayer.value?.lastName}")
                true
            }
            // Drag has entered bounding box of a view
            DragEvent.ACTION_DRAG_ENTERED -> {
                Log.d("DRAG_DEBUG", "Entered: $targetSlotId")
                v.alpha = 0.5f
                true
            }
            // Drag has exited bounding box of a view
            DragEvent.ACTION_DRAG_EXITED -> {
                v.alpha = 1.0f
                true
            }
            // User has released drag shadow and it is within the bounding box of a view
            DragEvent.ACTION_DROP -> {
                val targetSlotId = resources.getResourceEntryName(v.id)
                val requiredPosForTarget = viewModel.getRequiredPositionForSlot(targetSlotId)

                val clipData = event.clipData
                val sourceId = clipData?.getItemAt(0)?.text?.toString()
                val pendingPlayer = viewModel.pendingPlayer.value

                when {
                    // CASE 1: SWAPPING BETWEEN SLOTS
                    sourceId != null && sourceId.contains("slot") -> {
                        val sourcePlayer = viewModel.pitchOccupants.value[sourceId]
                        val targetPlayer = viewModel.pitchOccupants.value[targetSlotId]
                        val requiredPosForSource = viewModel.getRequiredPositionForSlot(sourceId)

                        // Check: Can the source player move to target?
                        // AND (if target isn't empty) can the target player move to source?
                        val canSourceMove = sourcePlayer?.positions?.contains(requiredPosForTarget) == true
                        val canTargetMove = targetPlayer == null || targetPlayer.positions.contains(requiredPosForSource)

                        if (canSourceMove && canTargetMove) {
                            viewModel.swapPlayers(sourceId, targetSlotId)
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        } else {
                            val errorName = if (!canSourceMove) sourcePlayer?.lastName else targetPlayer?.lastName
                            val errorPos = if (!canSourceMove) requiredPosForTarget else requiredPosForSource

                            android.widget.Toast.makeText(requireContext(), "$errorName cannot play $errorPos", android.widget.Toast.LENGTH_SHORT).show()
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                            viewModel.resetRecordingState()
                        }
                    }

                    // CASE 2: ASSIGNING FROM BENCH
                    pendingPlayer != null -> {
                        if (pendingPlayer.positions.contains(requiredPosForTarget)) {
                            viewModel.assignPlayerToSlot(pendingPlayer, targetSlotId)
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        } else {
                            android.widget.Toast.makeText(requireContext(),
                                "${pendingPlayer.lastName} cannot play $requiredPosForTarget",
                                android.widget.Toast.LENGTH_SHORT).show()

                            v.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                            viewModel.resetRecordingState() // Clean up the pending player state
                        }
                    }

                    else -> Log.e("DRAG_DEBUG", "Drop failed: No valid source.")
                }
                true
            }
            // Drag and drop operation has concluded
            DragEvent.ACTION_DRAG_ENDED -> {
                v.alpha = 1.0f
                Log.d("DRAG_DEBUG", "DRAG ENDED - Result: ${event.result}")
                true
            }
            else -> false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPitchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBench()
        setupHoopListeners()
        setupFieldInteraction()

        // Creates a coroutine that is automatically canceled on lifecycle death
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // Observes changes in the gamePhase, changing the visibility of the seeker slot
                launch {
                    viewModel.gamePhase.collect { phase ->
                        binding.slotSeeker.root.visibility = if (phase == GamePhase.SECOND_HALF) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.statEventMessage.collect { message ->
                        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                launch {
                    viewModel.currentPossessionType.collect { type ->
                        val color = if (type == PossessionType.OFFENSE) {
                            Color.parseColor("#2D5A27") // Dark Green
                        } else {
                            Color.parseColor("#631010") // Dark Red
                        }

                        // Smooth transition for the background color
                        binding.pitchBackground.setBackgroundColor(color)

                        binding.btnOppTurnover.visibility = if (type == PossessionType.DEFENSE) View.VISIBLE else View.GONE
                    }
                }

                // Observes changes in the uiState
                launch {
                    viewModel.uiState.collect { state ->
                        binding.btnCancelRecording.visibility = if (state is RecordingState.Idle) View.GONE else View.VISIBLE

                        when (state) {
                            is RecordingState.SelectingAction -> showActionMenu(state.player)
                            is RecordingState.SelectingShotResult -> showShotResultDialog()
                            is RecordingState.SelectingHoop ->  {
                                highlightOpposingHoops(true)

                                android.widget.Toast.makeText(
                                    requireContext(),
                                    if (state.isGood) "SELECT THE HOOP WHICH WAS SCORED ON" else "SELECT THE HOOP WHICH WAS SHOT AT",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            is RecordingState.SelectingShotType -> {
                                highlightOpposingHoops(false)
                                showShotTypeMenu(state.isGood)
                            }
                            is RecordingState.SelectingAssistantDecision -> showAssistDecisionDialog()
                            is RecordingState.SelectingAssistant -> highlightTeammates(true, state.player.id)

                            is RecordingState.SelectingConcededGoalType -> {
                                // Show the same menu, but have it call finalizeConcededGoal
                                showConcededGoalTypeMenu()
                            }

                            is RecordingState.Idle -> {
                                highlightTeammates(false, "")
                                highlightOpposingHoops(false)
                                // Dismiss any open menus
                            }
                        }
                    }
                }

                // Observes changes in isRosterExpanded, instituting the UI changes from one to the other
                // This includes updating the field slot dimensions, the hoop dimensions, and the position flag dimensions
                launch {
                    viewModel.isBenchExpanded.collect { expanded ->
                        androidx.transition.TransitionManager.beginDelayedTransition(binding.root as ViewGroup)

                        binding.benchContainer.visibility = if (expanded) View.VISIBLE else View.GONE
                        binding.fabToggleBench.setImageResource(
                            if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less
                        )

                        val slotWidth = if (expanded) 65.dpToPx() else 110.dpToPx()
                        val slotHeight = if (expanded) 85.dpToPx() else 145.dpToPx()
                        val hoopSize = if (expanded) 25.dpToPx() else 50.dpToPx()

                        val newFlagSize = if (expanded) 25.dpToPx() else 45.dpToPx()
                        val newBadgeTextSize = if (expanded) 5f else 8f
                        val newTopMargin = if (expanded) 6.dpToPx() else 10.dpToPx()
                        val newEndMargin = if (expanded) (-1).dpToPx() else 0.dpToPx()

                        val allSlots = listOf(
                            binding.slotKeeper, binding.slotChaserTop,
                            binding.slotChaserHoopsLeft, binding.slotChaserHoopsRight,
                            binding.slotBeater1, binding.slotBeater2, binding.slotSeeker
                        )

                        allSlots.forEach { slot ->
                            slot.root.updateLayoutParams {
                                width = slotWidth
                                height = slotHeight
                            }

                            slot.flagContainer.updateLayoutParams {
                                width = newFlagSize
                                height = newFlagSize
                            }

                            slot.positionBadgeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, newBadgeTextSize)

                            slot.positionBadgeText.updateLayoutParams<FrameLayout.LayoutParams> {
                                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                                topMargin = newTopMargin
                                marginEnd = newEndMargin
                            }
                        }

                        val allHoops = listOf(
                            binding.hoopTxqTall, binding.hoopTxqMedium, binding.hoopTxqSmall,
                            binding.hoopOppTall, binding.hoopOppMedium, binding.hoopOppSmall
                        )

                        allHoops.forEach { hoop ->
                            hoop.setPadding(0, 0, 0, 0)
                            hoop.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            hoop.updateLayoutParams<LinearLayout.LayoutParams> {
                                width = hoopSize
                                height = hoopSize
                                weight = 0f
                            }
                        }
                    }
                }

                // Observes changes in pitchOccupants and updates the UI for each slot when there is a change
                launch {
                    viewModel.pitchOccupants.collect { occupants ->
                        updateSlotUi(binding.slotKeeper, occupants["slot_keeper"], "KEEPER")
                        updateSlotUi(binding.slotChaserTop, occupants["slot_chaser_top"], "CHASER")
                        updateSlotUi(binding.slotChaserHoopsLeft, occupants["slot_chaser_hoops_left"], "CHASER")
                        updateSlotUi(binding.slotChaserHoopsRight, occupants["slot_chaser_hoops_right"], "CHASER")
                        updateSlotUi(binding.slotBeater1, occupants["slot_beater_1"], "BEATER")
                        updateSlotUi(binding.slotBeater2, occupants["slot_beater_2"], "BEATER")
                        updateSlotUi(binding.slotSeeker, occupants["slot_seeker"], "SEEKER")
                    }
                }
            }
        }

        // When the FAB is clicked, toggle the roster visibility
        binding.fabToggleBench.setOnClickListener { viewModel.toggleBenchVisibility() }

        binding.btnCancelRecording.setOnClickListener {
            viewModel.resetRecordingState()
        }

        binding.btnOppTurnover.setOnClickListener {
            viewModel.switchPossession()
        }
    }

    /**
     * Sets the behavior for the field slots.
     */
    private fun setupFieldInteraction() {
        val fieldSlots = listOf(
            binding.slotKeeper, binding.slotChaserTop,
            binding.slotChaserHoopsLeft, binding.slotChaserHoopsRight,
            binding.slotBeater1, binding.slotBeater2, binding.slotSeeker
        )

        // For every slot sets the onDragListener, onLongClickListener, onClickListener
        fieldSlots.forEach { slotBinding ->
            val slotId = resources.getResourceEntryName(slotBinding.root.id)
            slotBinding.root.setOnDragListener(slotDragListener)

            // On a long click allows the selected player to be dragged to another slot
            slotBinding.root.setOnLongClickListener { view ->
                val occupant = viewModel.pitchOccupants.value[slotId]
                if (occupant != null) {
                    val data = android.content.ClipData.newPlainText("source_slot_id", slotId)
                    val shadow = View.DragShadowBuilder(view)
                    view.startDragAndDrop(data, shadow, null, 0)
                    true
                } else false
            }

            // On a short click either adds a player to that slot or starts the sub process using that empty slot
            slotBinding.root.setOnClickListener {
                val occupant = viewModel.pitchOccupants.value[slotId]

                // If a player was clicked on in the bench then this tap completes the substitution
                if (viewModel.pendingPlayer.value != null) {
                    viewModel.assignPlayerToSlot(viewModel.pendingPlayer.value!!, slotId)
                } else if (viewModel.pendingSlot.value != null) {
                    val currentMap = viewModel.pitchOccupants.value.toMap()
                    if (currentMap[viewModel.pendingSlot.value] == null && currentMap[slotId] == null) {
                        viewModel.resetRecordingState()

                        val requiredPos = viewModel.getRequiredPositionForSlot(slotId)

                        viewModel.setPositionFilter(requiredPos)

                        viewModel.startSubProcessSlot(slotId, requiredPos)
                    } else {
                        viewModel.swapPlayers(slotId, viewModel.pendingSlot.value.toString())
                    }
                } else if (occupant == null) {
                    val requiredPos = viewModel.getRequiredPositionForSlot(slotId)

                    viewModel.setPositionFilter(requiredPos)

                    viewModel.startSubProcessSlot(slotId, requiredPos)
                } else if (viewModel.uiState.value is RecordingState.SelectingAssistant) {
                    val occupant = viewModel.pitchOccupants.value[slotId]
                    if (occupant?.id == (viewModel.uiState.value as RecordingState.SelectingAssistant).player.id) {
                        viewModel.completeGoal(null)
                    } else if (occupant != null) {
                        viewModel.completeGoal(occupant)
                    }
                } else {
                    Log.d("PITCH", "Slot $slotId clicked")
                    viewModel.initiateAction(occupant)
                }
            }
        }
    }

    /**
     * Updates the UI for each slot.
     */
    private fun updateSlotUi(
        slotBinding: ItemFieldSlotBinding,
        player: Player?,
        defaultRole: String
    ) {
        // Updates the position badge text in the top corner of the slot
        slotBinding.positionBadgeText.text = defaultRole.uppercase()

        // Gets the color of the given position for the slot and updates the flag to be that color
        val positionColor = when (defaultRole.uppercase()) {
            "KEEPER" -> Color.GREEN
            "CHASER" -> Color.WHITE
            "BEATER" -> Color.BLACK
            "SEEKER" -> Color.YELLOW
            else -> Color.RED
        }
        slotBinding.positionFlag.backgroundTintList = android.content.res.ColorStateList.valueOf(positionColor)
        slotBinding.positionBadgeText.setTextColor(if (positionColor == Color.BLACK) Color.WHITE else Color.BLACK)

        // If the slot is occupied, add the player's name and photo
        if (player != null) {
            slotBinding.tvSlotRole.text = player.name.uppercase()
            slotBinding.tvSlotRole.setTextColor(Color.WHITE)
            slotBinding.root.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.burnt_orange))
            slotBinding.root.strokeColor = Color.WHITE
            slotBinding.root.strokeWidth = 2

            // Loads the player's photo into the slot
            Glide.with(this)
                .load(player.photoResId ?: R.drawable.ic_player_placeholder)
                .into(slotBinding.ivPlayerPhoto)
        } else {
            // If the slot is empty, update the slot and clears the photo
            slotBinding.tvSlotRole.text = defaultRole
            slotBinding.tvSlotRole.setTextColor(Color.GRAY)
            slotBinding.ivPlayerPhoto.setImageDrawable(null)
            slotBinding.ivPlayerPhoto.setImageResource(0)
            slotBinding.root.setCardBackgroundColor(Color.parseColor("#26FFFFFF"))
            slotBinding.root.strokeColor = Color.parseColor("#40FFFFFF")
        }
    }

    /**
     * Creates a bench fragment using RosterFragment with an is_bench argument
     */
    private fun setupBench() {
        val benchFragment = RosterFragment().apply {
            arguments = Bundle().apply {
                putBoolean("is_bench", true)
            }
        }

        // This creates a sub-fragment that dies whenever the pitch fragment dies
        childFragmentManager.beginTransaction()
            .replace(R.id.bench_container, benchFragment)
            .commit()
    }

    /**
     * Scoring logic for the hoops. TODO
     */
    private fun setupHoopListeners() {
        val hoops = listOf(
            binding.hoopTxqMedium, binding.hoopTxqTall, binding.hoopTxqSmall,
            binding.hoopOppMedium, binding.hoopOppTall, binding.hoopOppSmall
        )

        hoops.forEach { hoop ->
            hoop.setOnClickListener {
                Log.d("PITCH_LOG", "Goal scored on hoop: ${resources.getResourceEntryName(it.id)}")
                // viewModel.recordGoal(it.id)
                val hoopIDName = resources.getResourceEntryName(it.id)

                val hoopID = when {
                    hoopIDName.contains("tall", ignoreCase = true) -> HoopID.TALL
                    hoopIDName.contains("medium", ignoreCase = true) -> HoopID.MEDIUM
                    else -> HoopID.SMALL
                }

                val isOpponentHoop = hoopIDName.contains("opp", ignoreCase = true)
                val isDefending = viewModel.currentPossessionType.value == PossessionType.DEFENSE

                if (isDefending && isOpponentHoop) return@setOnClickListener
                if (!isDefending && !isOpponentHoop) return@setOnClickListener

                when {
                    // OFFENSE: Recording a goal on opponent hoops
                    isOpponentHoop && viewModel.uiState.value is RecordingState.SelectingHoop -> {
                        viewModel.selectHoop(hoopID)
                    }
                    // DEFENSE: Recording a conceded goal on our hoops
                    !isOpponentHoop && isDefending -> {
                        viewModel.initiateConcededGoal(hoopID)
                    }
                }
            }
        }
    }

    /**
     * Shows menu for action attribution
     */
    private fun showActionMenu(player: Player) {
        val actions = arrayOf("Shot", "Turnover", "Turnover Forced", "Penalty")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Action: ${player.lastName.uppercase()}")
            .setItems(actions) { _, which ->
                val selectedAction = actions[which]
                viewModel.selectAction(selectedAction)
            }
            .setOnDismissListener {
                // If they tap outside, reset the state machine to Idle
                if (viewModel.uiState.value is RecordingState.SelectingAction) {
                    viewModel.resetRecordingState()
                }
            }
            .show()
    }

    /**
     * Highlights opposing hoops for hoop selection
     */
    private fun highlightOpposingHoops(shouldHighlight: Boolean) {
        val opposingHoops = listOf(binding.hoopOppTall, binding.hoopOppMedium, binding.hoopOppSmall)

        // Dim the pitch background to make hoops pop
        binding.pitchBackground.alpha = if (shouldHighlight) 0.5f else 1.0f

        opposingHoops.forEach { hoop ->
            if (shouldHighlight) {
                // Set pivot to the bottom center so the stem stays still
                hoop.pivotX = hoop.width / 2f
                hoop.pivotY = hoop.height.toFloat()

                hoop.animate().scaleX(1.3f).scaleY(1.3f).setDuration(300).start()

                val shake = android.animation.ObjectAnimator.ofFloat(hoop, View.ROTATION, -8f, 8f).apply {
                    duration = 360
                    repeatCount = android.animation.ObjectAnimator.INFINITE
                    repeatMode = android.animation.ObjectAnimator.REVERSE
                }
                shake.start()
                hoop.tag = shake
            } else {
                (hoop.tag as? android.animation.ObjectAnimator)?.cancel()
                hoop.animate().scaleX(1.0f).scaleY(1.0f).rotation(0f).setDuration(200).start()
            }
        }
    }

    /**
     * Pops up after a hoop has been selected, user selects what kind of goal was scored
     */
    private fun showShotTypeMenu(isGood: Boolean) {
        val types = arrayOf(ShotType.DUNK, ShotType.FINISH, ShotType.SHOT)

        val typeNames = types.map { enumValue ->
            enumValue.name.lowercase().replaceFirstChar { it.uppercase() }
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isGood) "How was it scored?" else "How was it shot?")
            .setItems(typeNames) { _, which ->
                val selectedType = types[which]
                viewModel.selectShotType(selectedType)

                view?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            }
            .setOnCancelListener { viewModel.resetRecordingState() }
            .setCancelable(true)
            .show()
    }

    private fun highlightTeammates(shouldHighlight: Boolean, scorerId: String) {
        val allSlots = listOf(
            binding.slotKeeper, binding.slotChaserTop,
            binding.slotChaserHoopsLeft, binding.slotChaserHoopsRight,
            binding.slotBeater1, binding.slotBeater2, binding.slotSeeker
        )

        // Dim the pitch background
        binding.pitchBackground.alpha = if (shouldHighlight) 0.5f else 1.0f

        allSlots.forEach { slotBinding ->
            val slotId = resources.getResourceEntryName(slotBinding.root.id)
            val occupant = viewModel.pitchOccupants.value[slotId]
            val view = slotBinding.root

            if (shouldHighlight && occupant != null) {
                if (occupant.id != scorerId) {

                    // 2. PULSE: Smoothly scale up and down
                    val pulseX = android.animation.ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.08f).apply {
                        duration = 600
                        repeatCount = android.animation.ObjectAnimator.INFINITE
                        repeatMode = android.animation.ObjectAnimator.REVERSE
                    }
                    val pulseY = android.animation.ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.08f).apply {
                        duration = 600
                        repeatCount = android.animation.ObjectAnimator.INFINITE
                        repeatMode = android.animation.ObjectAnimator.REVERSE
                    }

                    // 3. SHAKE: Tiny rotation for urgency
                    val shake = android.animation.ObjectAnimator.ofFloat(view, View.ROTATION, -1f, 1f).apply {
                        duration = 150
                        repeatCount = android.animation.ObjectAnimator.INFINITE
                        repeatMode = android.animation.ObjectAnimator.REVERSE
                    }

                    // Start them together
                    android.animation.AnimatorSet().apply {
                        playTogether(pulseX, pulseY, shake)
                        start()
                        view.tag = this // Store the set so we can cancel it later
                    }
                } else {
                    // Dim the scorer so they look "inactive" for selection
                    view.alpha = 0.5f
                }
            } else {
                // RESET TO NORMAL: Revert to the state defined in updateSlotUi
                (view.tag as? android.animation.AnimatorSet)?.cancel()
                view.animate().scaleX(1.0f).scaleY(1.0f).rotation(0f).setDuration(200).start()
                view.alpha = 1.0f
            }
        }
    }

    private fun showAssistDecisionDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Goal Recorded")
            .setMessage("Was there an assistant for this goal?")
            .setPositiveButton("Yes") { _, _ -> viewModel.setAssistantRequired(true) }
            .setNegativeButton("No") { _, _ -> viewModel.setAssistantRequired(false) }
            .setOnCancelListener { viewModel.resetRecordingState() }
            .setCancelable(true)
            .show()
    }

    private fun showShotResultDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Shot Result")
            .setPositiveButton("Good") { _, _ -> viewModel.setShotResult(true) }
            .setNegativeButton("Missed") { _, _ -> viewModel.setShotResult(false) }
            .setOnCancelListener { viewModel.resetRecordingState() }
            .setCancelable(true)
            .show()
    }

    private fun showConcededGoalTypeMenu() {
        val types = arrayOf(ShotType.DUNK, ShotType.FINISH, ShotType.SHOT)
        val typeNames = types.map { it.name.lowercase().capitalize() }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Goal Conceded: Shot Type?")
            .setItems(typeNames) { _, which ->
                viewModel.finalizeConcededGoal(types[which])
            }
            .setNegativeButton("Cancel") { _, _ -> viewModel.resetRecordingState() }
            .setOnCancelListener { viewModel.resetRecordingState() }
            .setCancelable(true)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Converts dp to pixels for layout changes
fun Int.dpToPx(): Int {
    return (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}