package com.orion.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.orion.app.R

/**
 * Mode selection screen - allows user to choose between Beacon mode and Navigator mode
 * Handles permission requests for Bluetooth and Location
 */
class ModeSelectionActivity : AppCompatActivity() {

    private lateinit var cardBeaconMode: MaterialCardView
    private lateinit var cardNavigatorMode: MaterialCardView
    private lateinit var cardLiveLocationMode: MaterialCardView

    private var pendingMode: Mode? = null

    private enum class Mode {
        BEACON, NAVIGATOR, LIVE_LOCATION
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            pendingMode?.let { navigateToMode(it) }
        } else {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show()
        }
        pendingMode = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        cardBeaconMode = findViewById(R.id.cardBeaconMode)
        cardNavigatorMode = findViewById(R.id.cardNavigatorMode)
        cardLiveLocationMode = findViewById(R.id.cardLiveLocationMode)
    }

    private fun setupListeners() {
        cardBeaconMode.setOnClickListener {
            checkPermissionsAndNavigate(Mode.BEACON)
        }

        cardNavigatorMode.setOnClickListener {
            checkPermissionsAndNavigate(Mode.NAVIGATOR)
        }
        
        cardLiveLocationMode.setOnClickListener {
            // Live Location doesn't need BLE permissions, just navigate directly
            navigateToMode(Mode.LIVE_LOCATION)
        }
    }

    private fun checkPermissionsAndNavigate(mode: Mode) {
        val permissions = getRequiredPermissions()
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            navigateToMode(mode)
        } else {
            pendingMode = mode
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        return permissions
    }

    private fun navigateToMode(mode: Mode) {
        val intent = when (mode) {
            Mode.BEACON -> Intent(this, BeaconActivity::class.java)
            Mode.NAVIGATOR -> Intent(this, DirectionActivity::class.java)
            Mode.LIVE_LOCATION -> Intent(this, RoleSelectionActivity::class.java)
        }
        startActivity(intent)
    }
}
