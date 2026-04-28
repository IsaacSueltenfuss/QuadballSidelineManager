package com.example.quadballsidelinemanager.features.roster

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.databinding.FragmentRosterBinding
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.QuadballPosition
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.quadballsidelinemanager.EnableType
import com.example.quadballsidelinemanager.MainViewModel
import com.example.quadballsidelinemanager.utils.ScaledDragShadowBuilder
import com.example.quadballsidelinemanager.SortType
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.getValue
import android.net.Uri
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import com.example.quadballsidelinemanager.MainActivity
import com.example.quadballsidelinemanager.MainViewModelFactory
import com.example.quadballsidelinemanager.utils.Storage
import com.google.android.material.button.MaterialButton

class RosterFragment : Fragment() {

    private var _binding: FragmentRosterBinding? = null
    private val binding get() = _binding!!

    // Gets the shared view model
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory((requireActivity() as MainActivity).authUser)
    }
    private lateinit var rosterAdapter: RosterAdapter

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            // This updates the preview in the dialog immediately!
            previewImageView?.setImageURI(it)
        }
    }

    private var selectedImageUri: Uri? = null
    private var previewImageView: ImageView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRosterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRosterBinding.bind(view)

        val isBenchMode = arguments?.getBoolean("is_bench", false) ?: false

        Log.d("BENCH_DEBUG", "Fragment Created. Is Bench Mode: $isBenchMode")

        binding.fabAddPlayer.setOnClickListener {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_player, null)

            previewImageView = dialogView.findViewById(R.id.ivPlayerPreview)

            dialogView.findViewById<View>(R.id.btnAddPhoto).setOnClickListener {
                pickImageLauncher.launch("image/*")
            }

            val etName = dialogView.findViewById<EditText>(R.id.etPlayerName)
            val etNumber = dialogView.findViewById<EditText>(R.id.etPlayerNumber)
            val genderToggle = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleGender)
            val spinnerPrimary = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerPrimaryPosition)

            val cbKeeper = dialogView.findViewById<CheckBox>(R.id.cbKeeper)
            val cbChaser = dialogView.findViewById<CheckBox>(R.id.cbChaser)
            val cbBeater = dialogView.findViewById<CheckBox>(R.id.cbBeater)
            val cbSeeker = dialogView.findViewById<CheckBox>(R.id.cbSeeker)

            val checkboxes = mapOf(
                QuadballPosition.KEEPER to cbKeeper,
                QuadballPosition.CHASER to cbChaser,
                QuadballPosition.BEATER to cbBeater,
                QuadballPosition.SEEKER to cbSeeker
            )

            // 4. Setup Primary Position Dropdown
            val positions = QuadballPosition.values().map { it.name }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, positions)
            spinnerPrimary.setAdapter(adapter)

            spinnerPrimary.setText(QuadballPosition.KEEPER.name, false)

            val genderId = R.id.btnGenderMale
            genderToggle.check(genderId)

            // 6. Pre-populate checkboxes and lock the Primary
            checkboxes[QuadballPosition.KEEPER]?.isChecked = true
            checkboxes[QuadballPosition.KEEPER]?.isEnabled = false

            spinnerPrimary.setOnItemClickListener { _, _, index, _ ->
                val selectedPos = QuadballPosition.valueOf(positions[index])

                // Re-enable everything then lock the new selection
                checkboxes.values.forEach { it.isEnabled = true }
                checkboxes[selectedPos]?.apply {
                    isChecked = true
                    isEnabled = false
                }
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add New Player")
                .setView(dialogView)
                .setPositiveButton("Add") { _, _ ->
                    val finalName = etName.text.toString()
                    val finalNumber = etNumber.text.toString().toIntOrNull() ?: 0
                    val finalPrimary = QuadballPosition.valueOf(spinnerPrimary.text.toString())

                    val finalGender = when (genderToggle.checkedButtonId) {
                        R.id.btnGenderMale -> GenderIdentity.MALE
                        R.id.btnGenderFemale -> GenderIdentity.FEMALE
                        R.id.btnGenderNb -> GenderIdentity.NON_BINARY
                        else -> GenderIdentity.OTHER
                    }

                    spinnerPrimary.setText(finalPrimary.toString().uppercase(), false)

                    val finalPositions = checkboxes.filter { it.value.isChecked }.keys.toSet()

                    // Push update to ViewModel using the Immutable Copy pattern
                    val player = viewModel.addNewPlayerToRoster(
                        finalName, finalGender, finalNumber, finalPrimary, finalPositions
                    )

                    selectedImageUri?.let { uri ->
                        uploadImage(uri, player.id)
                    }

                    // 3. Reset for next time
                    selectedImageUri = null
                    previewImageView = null
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        setupHeader(isBenchMode)
        setupAdapter(isBenchMode)
        setupRecyclerView(isBenchMode)
        observeViewModel(isBenchMode)
    }

    private fun uploadImage(uri: Uri, playerId: String) {
        val tempFile = File(requireContext().cacheDir, "temp_upload.jpg")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }

        val storage = com.example.quadballsidelinemanager.utils.Storage()
        storage.uploadImage(tempFile, playerId) {
            // Once upload is done, refresh the roster to show the new photo
            viewModel.loadRoster()
        }
    }

    /**
     * Sets up the header size and title based on isBenchMode
     */
    private fun setupHeader(isBenchMode: Boolean) {
        binding.rosterHeader.apply {
            headerTitle.setTextColor(Color.WHITE)
            headerTitle.text = if (isBenchMode) "${viewModel.currentTeam.value.teamAcronym.uppercase()} BENCH" else "${viewModel.currentTeam.value.teamName.uppercase()} ROSTER"
            headerTitle.textSize = if (isBenchMode) 14f else 18f

            if (isBenchMode) {
                root.updateLayoutParams { height = 60.dpToPx() }
            }

            // Enables the left header button and adds an icon and listener
            headerLeftIcon.visibility = View.VISIBLE
            headerLeftIcon.setImageResource(R.drawable.ic_sort)
            headerLeftIcon.setOnClickListener {
                showSortMenu()
            }

            // Enables the right header button and adds an icon and listener
            headerRightIcon.visibility = View.VISIBLE
            headerRightIcon.setImageResource(R.drawable.ic_filter)
            headerRightIcon.setOnClickListener { showFilterMenu() }
        }
    }

    /**
     * Initializes the RosterAdapter
     */
    private fun setupAdapter(isBenchMode: Boolean) {
        rosterAdapter = RosterAdapter(
            // On a short click, either assigns a player to a slot if a slot has already been selected
            // or starts a substitution process if in bench mode, or opens the player stats page
            onPlayerClick = { player ->
                val targetSlotId = viewModel.pendingSlot.value

                if (isBenchMode && targetSlotId != null) {
                    viewModel.assignPlayerToSlot(player, targetSlotId, true)
                } else if (isBenchMode) {
                    viewModel.startSubProcessPlayer(player, isDragging = false)
                } else {
                    openPlayerDetails(player)
                }
            },
            // On a long click, either toggles the active status of a player if not in bench mode or
            // starts a sub process and the drag process if in bench mode
            onPlayerLongClick = { itemView, player ->
                Log.d("BENCH_DEBUG", "Long Click Registered in Fragment for: ${player.lastName}")
                if (isBenchMode) {
                    viewModel.startSubProcessPlayer(player, isDragging = true)
                    Log.d("BENCH_DEBUG", "2. VM Pending Player is now: ${viewModel.pendingPlayer.value?.lastName}")

                    // Resizes the shadow
                    val targetWidth = itemView.resources.getDimensionPixelSize(R.dimen.pitch_card_width)
                    val targetHeight = itemView.resources.getDimensionPixelSize(R.dimen.pitch_card_height)

                    val item = android.content.ClipData.Item(player.id)
                    val data = android.content.ClipData(
                        player.name,
                        arrayOf(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN),
                        item
                    )

                    // Uses custom drag builder
                    val shadow = ScaledDragShadowBuilder(itemView, targetWidth, targetHeight)

                    itemView.startDragAndDrop(data, shadow, null, 0)
                    itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                } else {
                    viewModel.toggleActivePlayerStatus(player)
                }
                true
            }
        )

        // Assigns the RosterAdapter
        binding.rosterRecyclerView.adapter = rosterAdapter
    }

    /**
     * Initializes the recycler view and sets the maximum amount of columns
     */
    private fun setupRecyclerView(isBenchMode: Boolean) {
        val columnWidth = try {
            resources.getDimensionPixelSize(
                if (isBenchMode) R.dimen.player_card_width else R.dimen.player_card_width
            )
        } catch (e: Exception) {
            (100 * resources.displayMetrics.density).toInt()
        }

        Log.d("DEBUG", "Column Width is of type ${columnWidth::class} and is $columnWidth")
        val columnCount = if (columnWidth > 0) {
            (resources.displayMetrics.widthPixels / columnWidth).coerceAtLeast(2)
        } else {
            2
        }
        val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)

        Log.d("DEBUG", "Screen: ${resources.displayMetrics.widthPixels}, Card: $columnWidth, Columns: $columnCount")

        // Applies the layout to the recycler view
        binding.rosterRecyclerView.apply {
            layoutManager = GridLayoutManager(context, columnCount)
            while (itemDecorationCount > 0) { removeItemDecorationAt(0) }
            addItemDecoration(GridSpacingItemDecoration(columnCount, spacing))
        }
    }

    /**
     * Observes changes in the view model
     */
    private fun observeViewModel(isBenchMode: Boolean) {
        // Launches coroutines that are automatically canceled upon lifecycle destruction
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Submits the list of relevant players to the RosterAdapter
                launch {
                    val sourceFlow = if (isBenchMode) viewModel.benchPlayers else viewModel.rosterTabPlayers
                    sourceFlow.collect { players ->
                        rosterAdapter.submitList(players)
                    }
                }

                launch {
                    combine(
                        viewModel.sortFilter,
                        viewModel.positionFilter,
                        viewModel.onlyActiveFilter
                    ) { sort, pos, active ->
                        Triple(sort, pos, active)
                    }.collect { (sort, pos, active) ->
                        // A. Update the header title (e.g., "Texas Roster BY PLUS/MINUS")
                        updateHeaderTitle(isBenchMode, sort, pos, active)

                        // B. Tell the adapter what stat to show in the statBar
                        rosterAdapter.displayMode = sort

                        // C. Force the adapter to re-draw the stat bars immediately
                        rosterAdapter.notifyDataSetChanged()
                    }
                }

                launch {
                    viewModel.pitchEnabled.collect { enableType ->
                        binding.fabAddPlayer.visibility = if (enableType == EnableType.NONE) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    /**
     * Displays the filter menu
     */
    private fun showFilterMenu() {
        val isBenchMode = arguments?.getBoolean("is_bench", false) ?: false
        val popup = PopupMenu(requireContext(), binding.rosterHeader.headerRightIcon)
        popup.menuInflater.inflate(R.menu.roster_filter_menu, popup.menu)

        // Set the initial checkbox state based on our variable
        val activeItem = popup.menu.findItem(R.id.menu_filter_active)
        if (isBenchMode) {
            activeItem.title = "Only On Field"
            activeItem.isChecked = viewModel.onlyPlayingFilter.value
        } else {
            activeItem.title = "Only Active"
            activeItem.isChecked = viewModel.onlyActiveFilter.value
        }

        // Sets on click listeners for when menu items are selected
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_filter_active -> {
                    val newCheckedState = !item.isChecked
                    item.isChecked = newCheckedState

                    // If in bench mode, toggles the onlyPlaying players
                    // If not in bench mode, toggles the onlyActive players
                    if (isBenchMode) {
                        viewModel.toggleOnlyPlayingFilter()
                    } else {
                        viewModel.toggleOnlyActiveFilter()
                    }

                }
                R.id.menu_pos_beater -> { viewModel.setPositionFilter(QuadballPosition.BEATER) }
                R.id.menu_pos_chaser -> { viewModel.setPositionFilter(QuadballPosition.CHASER) }
                R.id.menu_pos_keeper -> { viewModel.setPositionFilter(QuadballPosition.KEEPER) }
                R.id.menu_pos_all -> { viewModel.setPositionFilter(null) }
            }
            true
        }
        popup.show()
    }

    /**
     * Updates the title of the header based on sorts, filters, and isBenchMode
     */
    private fun updateHeaderTitle(isBenchMode: Boolean, sort: SortType, pos: QuadballPosition?, active: Boolean) {
        // If in benchMode, sets title to "LINEUP"/"BENCH" "BY" sort
        if (isBenchMode) {
            val isOnlyPlaying = viewModel.onlyPlayingFilter.value
            val modeText = if (isOnlyPlaying) "${viewModel.currentTeam.value.teamAcronym.uppercase()} LINEUP" else "${viewModel.currentTeam.value.teamAcronym.uppercase()} BENCH"
            val posText = pos?.let { "${it.name}S".uppercase() } ?: ""
            val titleParts = listOf(modeText, posText, "BY", sort.name)
                .filter { !it.isNullOrBlank() }

            binding.rosterHeader.headerTitle.text = titleParts.joinToString(" ")
        } else {
            // If not in benchMode, sets title to "ACTIVE"/"TEXAS" pos "BY" sort
            val statusText = if (active) "ACTIVE" else viewModel.currentTeam.value.teamAcronym.uppercase()
            val posText = pos?.let { "${it.name}S".uppercase() } ?: "ROSTER"
            binding.rosterHeader.headerTitle.text = "$statusText $posText BY ${sort.name}"
        }
    }

    /**
     * Opens the PlayerStatsFragment for a specific player
     */
    private fun openPlayerDetails(player: Player) {
        val detailSheet = PlayerStatsFragment(player)
        detailSheet.show(childFragmentManager, "PlayerDetailSheet")
    }

    /**
     * Shows the sort menu when the left icon is clicked.
     */
    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.rosterHeader.headerLeftIcon)
        popup.menuInflater.inflate(R.menu.roster_sort_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sort_position -> viewModel.updateSort(SortType.POSITION)
                R.id.sort_number -> viewModel.updateSort(SortType.NUMBER)
                R.id.sort_lastName -> viewModel.updateSort(SortType.NAME)
                R.id.sort_gender -> viewModel.updateSort(SortType.GENDER)
                R.id.sort_possessions -> viewModel.updateSort(SortType.POSSESSIONS)
                R.id.sort_plusMinus -> viewModel.updateSort(SortType.PLUS_MINUS)
                R.id.sort_reset -> viewModel.updateSort(SortType.POSITION)
            }
            true
        }
        popup.show()
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