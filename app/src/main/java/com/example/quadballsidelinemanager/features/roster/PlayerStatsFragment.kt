package com.example.quadballsidelinemanager.features.roster

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.quadballsidelinemanager.MainViewModel
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.databinding.FragmentPlayerDetailsBinding
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.QuadballPosition
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class PlayerStatsFragment(private val player: Player) : BottomSheetDialogFragment() {

    private var _binding: FragmentPlayerDetailsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            // Updates basic player info (TODO move this into separate tab and add gender/position changes)
            detailPlayerName.text = player.name.uppercase()
            detailPlayerNumber.text = "#${player.number}"
            detailPlayerPosition.text = player.positions.firstOrNull().toString()

            // Player stats
            statGoals.text = "Goals: ${player.totalGoals}"
            statGoalLocations.text = "Goal Locations: S (${player.goalsOnSmallHoop}), T (${player.goalsOnTopHoop}), M (${player.goalsOnMediumHoop})"
            val shotAccuracy = if (player.totalShotsTaken > 0) "%.2f".format((player.totalGoals.toDouble() / player.totalShotsTaken.toDouble()) * 100) else 0.00
            statShots.text = "Shots: ${player.totalShotsTaken} ($shotAccuracy%)"
            statShotLocations.text = "Shot Locations: S (${player.shotsOnSmallHoop}), T (${player.shotsOnTopHoop}), M (${player.shotsOnMediumHoop})"
            statAssists.text = "Assists: ${player.totalAssists}"
            statTurnovers.text = "Turnovers: ${player.totalTurnovers}"
            val pm = player.plusMinus
            statPlusMinus.text = "+/-: ${if (pm > 0) "+$pm" else pm}"
            statPossessions.text = "Poss (O:D): ${player.totalOffensivePossessions}:${player.totalDefensivePossessions}"
            statEfficiency.text = "Offensive Efficiency: ${String.format("%.2f", player.offensiveEfficiency)}"

            // Listener for edit button
            btnEditPlayer.setOnClickListener {
                showEditDialog()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showEditDialog() {
        // 1. Get the most up-to-date player data from the ViewModel
        val currentPlayer = viewModel.allPlayers.value.find { it.id == player.id } ?: player

        // 2. Inflate the dialog layout
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_player, null)

        // 3. Find UI References
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

        // 5. Pre-populate basic info
        etName.setText(currentPlayer.name)
        etNumber.setText(currentPlayer.number.toString())
        spinnerPrimary.setText(currentPlayer.primaryPosition.name, false)

        val genderId = when(currentPlayer.gender) {
            GenderIdentity.MALE -> R.id.btnGenderMale
            GenderIdentity.FEMALE -> R.id.btnGenderFemale
            GenderIdentity.NON_BINARY -> R.id.btnGenderNb
            else -> R.id.btnGenderOther
        }
        genderToggle.check(genderId)

        // 6. Pre-populate checkboxes and lock the Primary
        currentPlayer.positions.forEach { pos -> checkboxes[pos]?.isChecked = true }
        checkboxes[currentPlayer.primaryPosition]?.apply {
            isChecked = true
            isEnabled = false
        }

        // 7. Dynamic Locking Listener: Update locks when Primary Position changes
        spinnerPrimary.setOnItemClickListener { _, _, index, _ ->
            val selectedPos = QuadballPosition.valueOf(positions[index])

            // Re-enable everything then lock the new selection
            checkboxes.values.forEach { it.isEnabled = true }
            checkboxes[selectedPos]?.apply {
                isChecked = true
                isEnabled = false
            }
        }

        // 8. Build and Show Dialog
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit Player Info")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                // Map final values
                val finalName = etName.text.toString()
                val finalNumber = etNumber.text.toString().toIntOrNull() ?: 0
                val finalPrimary = QuadballPosition.valueOf(spinnerPrimary.text.toString())

                val finalGender = when (genderToggle.checkedButtonId) {
                    R.id.btnGenderMale -> GenderIdentity.MALE
                    R.id.btnGenderFemale -> GenderIdentity.FEMALE
                    R.id.btnGenderNb -> GenderIdentity.NON_BINARY
                    else -> GenderIdentity.OTHER
                }

                binding.detailPlayerPosition.text = finalPrimary.toString().uppercase()

                val finalPositions = checkboxes.filter { it.value.isChecked }.keys.toSet()

                // Push update to ViewModel using the Immutable Copy pattern
                viewModel.updatePlayerInfo(
                    currentPlayer, finalName, finalGender, finalNumber, finalPositions, finalPrimary
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}