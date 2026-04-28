package com.example.quadballsidelinemanager.features.summaries

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.quadballsidelinemanager.MainActivity
import com.example.quadballsidelinemanager.MainViewModel
import com.example.quadballsidelinemanager.MainViewModelFactory
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.databinding.FragmentSummaryBinding
import com.example.quadballsidelinemanager.models.HoopID
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.PossessionResult
import com.example.quadballsidelinemanager.models.PossessionType
import kotlinx.coroutines.launch

class StatsSummary : Fragment() {

    private var _binding: FragmentSummaryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory((requireActivity() as MainActivity).authUser)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        observeStats()
    }

    private fun setupHeader() {
        binding.summaryHeader.headerTitle.text = "LIVE ANALYTICS"
        binding.summaryHeader.headerRightIcon.visibility = View.INVISIBLE
    }

    private fun observeStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe the master list of players to calculate team-wide stats
                viewModel.allPlayers.collect { players -> // cite: 3
                    updateUI(players)
                }
            }
        }
    }

    private fun updateUI(players: List<Player>) {
        if (players.isEmpty()) return

        // 1. Calculate Aggregates
        val totalGoals = players.sumOf { it.totalGoals }
        val totalShots = players.sumOf { it.totalShotsTaken }
        val totalGoalsConceded = players
            .flatMap { it.possessions } // Get all possessions from all players
            .distinctBy { it.id }       // Ensure each team possession is only counted once
            .count { it.possessionType == PossessionType.DEFENSE && it.result == PossessionResult.CONCEDED_GOAL } //        val totalShots = players.sumOf { it.totalShotsTaken }
        val totalTeamPossessions = players
            .flatMap { it.possessions } // Combine every player's possession list into one
            .distinctBy { it.id }       // Filter out duplicates based on the unique possession ID
            .size
        val totalOffensiveTeamPossessions = players
            .flatMap { it.possessions } // Combine every player's possession list into one
            .filter { it.possessionType == PossessionType.OFFENSE }
            .distinctBy { it.id }       // Filter out duplicates based on the unique possession ID
            .size
        val totalTurnovers = players.sumOf { it.totalTurnovers }

        val shootingPerc = if (totalShots > 0) (totalGoals.toDouble() / totalShots) * 100 else 0.0

        // 2. Update Snapshot Cards
        // Assuming you have a custom binding for the mini-cards
        binding.statScoreTeam.tvStatLabel.text = "${viewModel.currentTeam.value.teamAcronym.uppercase()} SCORE"
        binding.statScoreTeam.tvStatValue.text = "${viewModel.currentTeamScore.value}"
        binding.statScoreOpp.tvStatLabel.text = "${viewModel.opposingTeam.value.uppercase()} SCORE"
        binding.statScoreOpp.tvStatValue.text = "${viewModel.oppTeamScore.value}"

        val conversionRate = if (totalOffensiveTeamPossessions > 0) totalGoals.toDouble() * 100 / totalOffensiveTeamPossessions.toDouble() else 0.0

        // 3. Update Ratio Bar
        binding.tvPossessionRatio.text = "Goals: $totalGoals | Turnovers: $totalTurnovers | ${String.format("%.2f", conversionRate)}% Conversion"
        val ratio = if (totalOffensiveTeamPossessions > 0)
            (totalGoals.toFloat() / totalOffensiveTeamPossessions * 100).toInt() else 0
        binding.progressGoalRatio.setProgress(ratio, true)

        val topScorersPerPossession = players
            .filter { it.totalOffensivePossessions > 3 } // Filter for a minimum sample size
            .sortedByDescending { it.goalsPerPossession }
            .take(3)
        val scorersText = topScorersPerPossession.joinToString("\n") { player ->
            "${player.lastName}: ${String.format("%.2f", player.goalsPerPossession)}" }
        binding.tvMostGoals.text = if (scorersText.isEmpty()) "A minimum of 3 offensive possessions is required." else scorersText

        val topAssistsPerPossession = players
            .filter { it.totalOffensivePossessions > 3 } // Filter for a minimum sample size
            .sortedByDescending { it.assistsPerPossession }
            .take(3)
        val assistsText = topAssistsPerPossession.joinToString("\n") { player ->
            "${player.lastName}: ${String.format("%.2f", player.assistsPerPossession)}" }
        binding.tvMostAssists.text = if (assistsText.isEmpty()) "A minimum of 3 offensive possessions is required." else assistsText

        val topPlusMinusPerPossession = players
            .filter { it.totalOffensivePossessions > 3 } // Filter for a minimum sample size
            .sortedByDescending { it.plusMinusPerPossession }
            .take(3)
        val topPlusMinusText = topPlusMinusPerPossession.joinToString("\n") { player ->
            "${player.lastName}: ${String.format("%.2f", player.plusMinusPerPossession)}" }
        binding.tvBestPlusMinus.text = if (topPlusMinusText.isEmpty()) "A minimum of 3 offensive possessions is required." else topPlusMinusText

        val leastPlusMinusPerPossession = players
            .filter { it.totalOffensivePossessions > 3 } // Filter for a minimum sample size
            .sortedBy { it.plusMinusPerPossession }
            .take(3)
        val leastPlusMinusText = leastPlusMinusPerPossession.joinToString("\n") { player ->
            "${player.lastName}: ${String.format("%.2f", player.plusMinusPerPossession)}" }
        binding.tvLeastPlusMinus.text = if (leastPlusMinusText.isEmpty()) "A minimum of 3 offensive possessions is required." else leastPlusMinusText

        // 4. Usage Leaders (Sorting players by possessions)
        val mostUsedPlayers = players.sortedByDescending { it.totalPossessions }.take(3)
        val highUsageText = mostUsedPlayers.joinToString("\n") { "${it.lastName}: ${it.totalPossessions}" }
        binding.tvHighUsage.text = highUsageText

        val leastUsedPlayers = players.sortedBy { it.totalPossessions }.take(3)
        val lowUsageText = leastUsedPlayers.joinToString("\n") { "${it.lastName}: ${it.totalPossessions}" }
        binding.tvLowUsage.text = lowUsageText

        updateHoopStats(players)

        val highestDodgeballsPerPossession = players
            .filter { it.dodgeballsOnDefense.isNotEmpty() }
            .filter { it.totalDefensivePossessions > 3 } // Filter for a minimum sample size
            .sortedByDescending { it.numDodgeballsPerPossession }
            .take(3)
        val highestDodgeballsPerPossessionText = highestDodgeballsPerPossession.joinToString("\n") { player ->
            "${player.lastName}: ${String.format("%.2f", player.numDodgeballsPerPossession)}" }
        binding.tvBestDodgeball.text = if (highestDodgeballsPerPossessionText.isEmpty()) "A minimum of 3 defensive possessions is required." else highestDodgeballsPerPossessionText

        val leastDodgeballsPerPossession = players
            .filter { it.dodgeballsOnDefense.isNotEmpty() }
            .filter { it.totalDefensivePossessions > 3 } // Filter for a minimum sample size
            .sortedBy { it.numDodgeballsPerPossession }
            .take(3)
        val leastDodgeballsPerPossessionText = leastDodgeballsPerPossession.joinToString("\n") { player ->
            "${player.lastName}: ${String.format("%.2f", player.numDodgeballsPerPossession)}" }
        binding.tvLeastDodgeball.text = if (leastDodgeballsPerPossessionText.isEmpty()) "A minimum of 3 defensive possessions is required." else leastDodgeballsPerPossessionText

    }

    private fun updateHoopStats(players: List<Player>) {
        // 1. Get all unique offensive shots from the team roster
        val offensiveShots = players.flatMap { it.possessions }
            .distinctBy { it.id } // Avoid double-counting players on the same pitch
            .filter { it.possessionType == PossessionType.OFFENSE } //
            .flatMap { it.shots } //

        val goalsConceded = players
            .flatMap { it.possessions } // Get all possessions from all players
            .distinctBy { it.id }       // Ensure each team possession is only counted once
            .filter { it.possessionType == PossessionType.DEFENSE && it.result == PossessionResult.CONCEDED_GOAL }
            .flatMap { it.shots }

        // 2. Helper to update a specific hoop's UI
        fun bindHoop(hoopView: View, hoopId: HoopID, label: String, isOpp: Boolean) {
            val shotsOnHoop = if (isOpp) offensiveShots.filter { it.hoopID == hoopId } else goalsConceded.filter { it.hoopID == hoopId }
            val goalsOnHoop = shotsOnHoop.count { it.isGood } //

            hoopView.findViewById<TextView>(R.id.tv_hoop_label).text = label
            hoopView.findViewById<TextView>(R.id.tv_hoop_stats).text = "$goalsOnHoop of ${shotsOnHoop.size}"

            val detailContainer = hoopView.findViewById<LinearLayout>(R.id.container_shot_details)
            detailContainer.removeAllViews()

            val shotsByType = shotsOnHoop.groupBy { it.shotType } //

            shotsByType.forEach { (type, typeShots) ->
                val typeGoals = typeShots.count { it.isGood }
                val typeTotal = typeShots.size
                val accuracy = (typeGoals.toDouble() / typeTotal) * 100

                // Create a small TextView for each shot type
                val tv = TextView(context).apply {
                    text = "${type.name.uppercase()}: ${accuracy.toInt()}%"
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    textSize = 9f
                    gravity = Gravity.CENTER
                    alpha = 0.8f
                }
                detailContainer.addView(tv)
            }

            val accuracy = if (shotsOnHoop.isNotEmpty()) goalsOnHoop.toDouble() * 100 / shotsOnHoop.size.toDouble() else 0.00
            hoopView.findViewById<TextView>(R.id.tv_hoop_percent).text = "${String.format("%.2f", accuracy)}%"

            // Optional: Dim the hoop icon if it hasn't been targeted yet
            hoopView.findViewById<View>(R.id.iv_hoop_icon).alpha = if (shotsOnHoop.isEmpty()) 0.3f else 1.0f
        }

        // 3. Bind each hoop section
        bindHoop(binding.oppHoopSmall.root, HoopID.SMALL, "SMALL", true)
        bindHoop(binding.oppHoopTall.root, HoopID.TALL, "TALL", true)
        bindHoop(binding.oppHoopMedium.root, HoopID.MEDIUM, "MEDIUM", true)

        bindHoop(binding.txqHoopMedium.root, HoopID.MEDIUM, "MEDIUM", false)
        bindHoop(binding.txqHoopTall.root, HoopID.TALL, "TALL", false)
        bindHoop(binding.txqHoopSmall.root, HoopID.SMALL, "SMALL", false)
    }
}