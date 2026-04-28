package com.example.quadballsidelinemanager.features.roster

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.example.quadballsidelinemanager.MainActivity
import com.example.quadballsidelinemanager.MainViewModel
import com.example.quadballsidelinemanager.MainViewModelFactory
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.databinding.FragmentPlayerDetailsBinding
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.QuadballPosition
import com.example.quadballsidelinemanager.utils.Storage
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

class PlayerStatsFragment(private val player: Player) : BottomSheetDialogFragment() {

    private var _binding: FragmentPlayerDetailsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory((requireActivity() as MainActivity).authUser)
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            // This updates the preview in the dialog immediately!
            previewImageView?.setImageURI(it)
        }
    }

    private var selectedImageUri: Uri? = null
    private var previewImageView: ImageView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allPlayers.collect { allPlayers ->
                    val updatedPlayer = allPlayers.find { it.id == player.id } ?: player

                    binding.apply {
                        // Updates basic player info (TODO move this into separate tab and add gender/position changes)
                        detailPlayerName.text = updatedPlayer.name.uppercase()
                        detailPlayerNumber.text = "#${updatedPlayer.number}"
                        detailPlayerPosition.text = updatedPlayer.positions.firstOrNull().toString()

                        // Player stats
                        statGoals.text = "Goals: ${updatedPlayer.totalGoals}"
                        statGoalLocations.text = "Goal Locations: S (${updatedPlayer.goalsOnSmallHoop}), T (${updatedPlayer.goalsOnTopHoop}), M (${updatedPlayer.goalsOnMediumHoop})"
                        val shotAccuracy = if (updatedPlayer.totalShotsTaken > 0) "%.2f".format((updatedPlayer.totalGoals.toDouble() / updatedPlayer.totalShotsTaken.toDouble()) * 100) else 0.00
                        statShots.text = "Shots: ${updatedPlayer.totalShotsTaken} ($shotAccuracy%)"
                        statShotLocations.text = "Shot Locations: S (${updatedPlayer.shotsOnSmallHoop}), T (${updatedPlayer.shotsOnTopHoop}), M (${updatedPlayer.shotsOnMediumHoop})"
                        statAssists.text = "Assists: ${updatedPlayer.totalAssists}"
                        statTurnovers.text = "Turnovers: ${updatedPlayer.totalTurnovers}"
                        statDodgeballs.text = "Average Dodgeballs: ${updatedPlayer.numDodgeballsPerPossession}"
                        val pm = updatedPlayer.plusMinus
                        statPlusMinus.text = "+/-: ${if (pm > 0) "+$pm" else pm}"
                        statPossessions.text = "Poss (O:D): ${updatedPlayer.totalOffensivePossessions}:${updatedPlayer.totalDefensivePossessions}"
                        //statEfficiency.text = "Offensive Efficiency: ${String.format("%.2f", updatedPlayer.offensiveEfficiency)}"
                        beats.text = "Beats: ${updatedPlayer.beats}"

                        // Listener for edit button
                        btnEditPlayer.setOnClickListener {
                            showEditDialog()
                        }
                    }
                }
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

        val storage = Storage()

        storage.getPlayerHeadshot(player.id).addOnSuccessListener { uri ->
            Glide.with(this)
                .load(uri)
                .placeholder(R.drawable.ic_player_placeholder)
                .centerCrop()
                .into(previewImageView!!)
        }.addOnFailureListener {
            // Both extensions failed, Glide will stay on the placeholder
            Log.e("STORAGE", "No headshot found for ${player.id} (.jpg or .jpeg)")
        }

        // 2. Inflate the dialog layout
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_player, null)

        previewImageView = dialogView.findViewById(R.id.ivPlayerPreview)

        dialogView.findViewById<View>(R.id.btnAddPhoto).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

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
}