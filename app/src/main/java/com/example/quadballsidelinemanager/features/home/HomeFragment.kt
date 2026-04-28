package com.example.quadballsidelinemanager.features.home

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.example.quadballsidelinemanager.databinding.FragmentHomeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.quadballsidelinemanager.EnableType
import com.example.quadballsidelinemanager.GamePhase
import com.example.quadballsidelinemanager.MainActivity
import com.example.quadballsidelinemanager.MainViewModel
import com.example.quadballsidelinemanager.MainViewModelFactory
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.models.PossessionType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.getValue

class HomeFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory((requireActivity() as MainActivity).authUser)
    }
    private var _binding: FragmentHomeBinding? = null
    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        Log.d("NAV_CHECK", "HomeFragment Started")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Gets and updates current date and day of week
        val calendar = Calendar.getInstance()
        val formatter = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        val formattedDate = formatter.format(calendar.time).uppercase()

        binding.currentDateText.text = formattedDate

        // Updates game phase after a click to btnGamePhase
        binding.btnGamePhase.setOnClickListener {
            if (viewModel.gamePhase.value == GamePhase.DEFAULT) {
                viewModel.updateGamePhase(GamePhase.SEEKER_FLOOR)
            } else {
                viewModel.updateGamePhase(GamePhase.DEFAULT)
            }
        }

        // Confirms a flag catch and ends the Seeker Floor
        binding.btnOppFlagCatch.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirm Opponent Catch?")
                .setMessage("This will add 35 points to the opponent and end the Seeker Floor.")
                .setPositiveButton("Confirm") { _, _ ->
                    viewModel.finalizeOpponentFlagCatch(true)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Launches coroutines that are automatically canceled upon lifecycle destruction
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Updates the text on the button to reflect the current game phase
                launch {
                    viewModel.gamePhase.collect { phase ->
                        binding.btnGamePhase.text = when (phase) {
                            GamePhase.DEFAULT -> "FIRST PERIOD"
                            GamePhase.SEEKER_FLOOR -> "SEEKER FLOOR"
                            else -> "OTHER"
                        }
                    }
                }

                // Updates the team information
                launch {
                    viewModel.opposingTeam.collect { oppName ->
                        binding.teamInfo.text = "${viewModel.currentTeam.value.teamAcronym.uppercase()} VS. ${oppName.uppercase()}"
                    }
                }

                // Updates the UI state based on whether the pitch is enabled
                launch {
                    viewModel.pitchEnabled.collect { enableType ->
                        if (enableType == EnableType.GAME_IN_PROGRESS) {
                            binding.btnGameStart.text = "END GAME"

                            binding.teamInfo.visibility = View.VISIBLE
                            binding.teamInfo.text = "${viewModel.currentTeam.value.teamAcronym.uppercase()} vs. ${viewModel.opposingTeam.value.uppercase()}"
                            binding.streamLayout.visibility = View.VISIBLE
                            binding.btnGamePhase.visibility = View.VISIBLE
                            binding.btnGameLoad.visibility = View.GONE

                            binding.btnGameStart.setOnClickListener {
                                viewModel.exitGame()
                            }
                        } else if (enableType == EnableType.GAME_LOADED) {
                            binding.btnGameLoad.text = "EXIT GAME"

                            binding.teamInfo.visibility = View.VISIBLE
                            binding.teamInfo.text = "${viewModel.currentTeam.value.teamAcronym.uppercase()} vs. ${viewModel.opposingTeam.value.uppercase()}"
                            binding.streamLayout.visibility = View.VISIBLE
                            binding.btnGamePhase.visibility = View.VISIBLE
                            binding.btnGameStart.visibility = View.GONE

                            binding.btnGameLoad.setOnClickListener {
                                viewModel.exitGame()
                            }
                        } else {
                            binding.btnGameStart.visibility = View.VISIBLE
                            binding.btnGameLoad.visibility = View.VISIBLE

                            binding.btnGameStart.text = "START GAME"
                            binding.btnGameLoad.text = "JOIN GAME"

                            binding.teamInfo.visibility = View.GONE
                            binding.streamLayout.visibility = View.GONE
                            binding.btnGamePhase.visibility = View.GONE

                            binding.btnGameStart.setOnClickListener {
                                showStartGameDialog()
                            }

                            binding.btnGameLoad.setOnClickListener {
                                val input = EditText(requireContext())
                                input.hint = "TXQvsUTSA_2026-04-27"

                                AlertDialog.Builder(requireContext())
                                    .setTitle("Join Existing Game")
                                    .setView(input)
                                    .setPositiveButton("Join") { _, _ ->
                                        val id = input.text.toString().trim()
                                        if (id.isNotEmpty()) {
                                            viewModel.joinLiveGame(id)
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                }

                // Updates the UI state based on the game phase
                launch {
                    combine(
                        viewModel.gamePhase,
                        viewModel.pitchEnabled,
                        viewModel.isFlagCaught
                    ) { phase, enableType, isCaught ->
                        Triple(phase, enableType, isCaught)
                    }.collect { (phase, enableType, isCaught) ->
                        val isGameRunning = enableType == EnableType.GAME_IN_PROGRESS

                        binding.btnGamePhase.visibility = if (
                            isGameRunning &&
                            phase == GamePhase.DEFAULT &&
                            !isCaught
                        ) View.VISIBLE else View.GONE
                        binding.btnGamePhase.text = "ENTER SEEKER FLOOR"

                        binding.btnOppFlagCatch.visibility = if (
                            isGameRunning &&
                            phase == GamePhase.SEEKER_FLOOR
                        ) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showStartGameDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_game_setup, null)
        val etOpponent = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOpponentName)
        val etStream = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etStreamLink)
        val toggleGroup = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.togglePossession)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tournament Game Setup")
            .setView(dialogView)
            .setPositiveButton("Start Whistle") { _, _ ->
                // Save Team Name
                val teamName = etOpponent.text.toString()
                viewModel.updateOpposingTeam(if (teamName.isBlank()) "Opponent" else teamName)

                // Set Possession (Offense or Defense)
                if (toggleGroup.checkedButtonId == R.id.btnStartDefense) {
                    // If they picked defense, toggle away from the default Offense
                    viewModel.toggleStartingSide()
                }

                // Enable the Pitch and Navigate
                viewModel.startGame()

                val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                bottomNav.selectedItemId = R.id.dest_pitch
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}