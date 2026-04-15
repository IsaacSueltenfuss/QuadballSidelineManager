package com.example.quadballsidelinemanager.features.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.quadballsidelinemanager.databinding.FragmentRosterBinding
import com.example.quadballsidelinemanager.features.roster.RosterAdapter
import com.example.quadballsidelinemanager.models.GenderIdentity
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.QuadballPosition

class SettingsFragment : Fragment() {

    private var _binding: FragmentRosterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRosterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Restore the Mock Data for testing

        // 2. Setup the Grid and Adapter using the 'binding' object
        // Notice we use binding.rvRoster instead of findViewById
//        val adapter = RosterAdapter(null)
//        binding.rosterRecyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
//        binding.rosterRecyclerView.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}