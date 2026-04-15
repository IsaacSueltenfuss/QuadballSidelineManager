package com.example.quadballsidelinemanager.features.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.example.quadballsidelinemanager.MainViewModel
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.models.PossessionType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlin.getValue

class HomeFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
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
            if (viewModel.gamePhase.value == GamePhase.FIRST_HALF) {
                viewModel.updateGamePhase(GamePhase.SECOND_HALF)
            } else {
                viewModel.updateGamePhase(GamePhase.FIRST_HALF)
            }
        }

        // Launches coroutines that are automatically canceled upon lifecycle destruction
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Updates the text on the button to reflect the current game phase
                launch {
                    viewModel.gamePhase.collect { phase ->
                        binding.btnGamePhase.text = when (phase) {
                            GamePhase.FIRST_HALF -> "FIRST PERIOD"
                            GamePhase.SECOND_HALF -> "SECOND PERIOD"
                            else -> "OTHER"
                        }
                    }
                }

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
                                showEndGameDialog()
                            }
                        } else if (enableType == EnableType.GAME_LOADED) {
                            binding.btnGameLoad.text = "EXIT GAME"

                            binding.teamInfo.visibility = View.VISIBLE
                            binding.teamInfo.text = "${viewModel.currentTeam.value.teamAcronym.uppercase()} vs. ${viewModel.opposingTeam.value.uppercase()}"
                            binding.streamLayout.visibility = View.VISIBLE
                            binding.btnGamePhase.visibility = View.VISIBLE
                            binding.btnGameStart.visibility = View.GONE

                            binding.btnGameLoad.setOnClickListener {
                                showExitGameDialog()
                            }
                        } else {
                            binding.btnGameStart.visibility = View.VISIBLE
                            binding.btnGameLoad.visibility = View.VISIBLE

                            binding.btnGameStart.text = "START GAME"
                            binding.btnGameLoad.text = "LOAD GAME"

                            binding.teamInfo.visibility = View.GONE
                            binding.streamLayout.visibility = View.GONE
                            binding.btnGamePhase.visibility = View.GONE

                            binding.btnGameStart.setOnClickListener {
                                showStartGameDialog(false)
                            }

                            binding.btnGameLoad.setOnClickListener {
                                showStartGameDialog(true) // UPDATE THIS
                            }
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

    private fun showStartGameDialog(isLoad: Boolean) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_game_setup, null)
        val etOpponent = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOpponentName)
        val etStream = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etStreamLink)
        val toggleGroup = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.togglePossession)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tournament Game Setup")
            .setView(dialogView)
            .setPositiveButton("Start Whistle") { _, _ ->
                // 1. Save Team Name
                val teamName = etOpponent.text.toString()
                viewModel.updateOpposingTeam(if (teamName.isBlank()) "Opponent" else teamName)

                // 2. Set Possession (Offense or Defense)
                if (toggleGroup.checkedButtonId == R.id.btnStartDefense) {
                    // If they picked defense, toggle away from the default Offense
                    viewModel.toggleStartingSide()
                }

                // 3. Enable the Pitch and Navigate
                if (isLoad) {
                    viewModel.loadGame()
                } else {
                    viewModel.startGame()
                }

                // Optional: Auto-navigate to Pitch once started
                val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                bottomNav.selectedItemId = R.id.dest_pitch
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEndGameDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("End Game?")
            .setMessage("This will stop recording and lock the stats. Make sure you're ready to export!")
            .setPositiveButton("End & Save") { _, _ ->
                viewModel.stopGame() // This sets pitchEnabled to false
                // Here is where you'd trigger your Firebase Export logic!
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExitGameDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Exit Game?")
            .setMessage("This will stop playback. Statistics are saved!")
            .setPositiveButton("Exit") { _, _ ->
                viewModel.stopGame() // This sets pitchEnabled to false
                // Here is where you'd trigger your Firebase Export logic!
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}