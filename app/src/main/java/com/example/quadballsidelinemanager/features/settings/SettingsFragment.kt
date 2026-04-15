package com.example.quadballsidelinemanager.features.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.quadballsidelinemanager.MainViewModel
import com.example.quadballsidelinemanager.databinding.FragmentRosterBinding
import com.example.quadballsidelinemanager.databinding.FragmentSettingsBinding
import com.example.quadballsidelinemanager.features.roster.RosterAdapter
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.QuadballPosition
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cbGenderRule.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleGenderRule(isChecked)
        }

        binding.cbPositionRule.setOnCheckedChangeListener { _, isChecked ->
            viewModel.togglePositionRule(isChecked)
        }

        // 2. Observe the current state to keep the switch in sync
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isGenderRuleEnabled.collect { isEnabled ->
                        binding.cbGenderRule.isChecked = isEnabled
                    }
                }

                launch {
                    viewModel.isPositionRuleEnabled.collect { isEnabled ->
                        binding.cbPositionRule.isChecked = isEnabled
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}