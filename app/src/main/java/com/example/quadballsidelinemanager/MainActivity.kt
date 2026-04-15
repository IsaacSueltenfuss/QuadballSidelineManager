package com.example.quadballsidelinemanager

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.quadballsidelinemanager.databinding.ActivityMainBinding
import com.example.quadballsidelinemanager.models.*
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // Activity-level ViewModel that Fragments share
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Sets up the bottom nav controller
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (binding.bottomNav.selectedItemId != destination.id) {
                // Manually tell the bar to "highlight" the correct icon
                binding.bottomNav.menu.findItem(destination.id)?.isChecked = true
            }
        }

        binding.bottomNav.setOnItemReselectedListener { item ->
            Log.d("NAV_DEBUG", "RESELECTED: ${item.title}")
            if (item.itemId == R.id.dest_home) {
                navController.navigate(R.id.dest_home)
            }
        }

        lifecycleScope.launch {
            viewModel.pitchEnabled.collect { enableType ->
                val menu = binding.bottomNav.menu
                val pitchTab = menu.findItem(R.id.dest_pitch)
                val summaryTab = menu.findItem(R.id.dest_summary)

                // 1. Toggle visibility of the Pitch menu item
                if (enableType == EnableType.GAME_IN_PROGRESS || enableType == EnableType.GAME_LOADED) {
                    pitchTab.isVisible = true
                    summaryTab.isVisible = true
                } else {
                    pitchTab.isVisible = false
                    summaryTab.isVisible = false
                }

                // 2. Safety Redirection:
                // If we are currently ON the pitch and it gets disabled, kick back to Home.
                if (enableType == EnableType.NONE && navController.currentDestination?.id == R.id.dest_pitch) {
                    navController.navigate(R.id.dest_home)
                }
            }
        }
    }
}