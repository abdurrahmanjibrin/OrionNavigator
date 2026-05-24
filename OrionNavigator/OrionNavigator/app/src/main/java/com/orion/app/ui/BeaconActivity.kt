package com.orion.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.orion.app.R
import com.orion.app.data.BeaconInfo
import com.orion.app.data.BeaconRepository
import com.orion.app.service.BeaconAdvertiseService

/**
 * Beacon mode activity - turns the phone into a BLE beacon
 * User selects a location and the phone broadcasts that location's beacon
 */
class BeaconActivity : AppCompatActivity(), BeaconAdvertiseService.ServiceListener {

    private lateinit var btnBack: ImageButton
    private lateinit var spinnerLocation: Spinner
    private lateinit var viewStatusIndicator: View
    private lateinit var tvStatus: TextView
    private lateinit var tvBroadcastLocation: TextView
    private lateinit var btnToggleBeacon: MaterialButton

    private lateinit var beaconRepository: BeaconRepository
    private var beacons: List<BeaconInfo> = emptyList()
    private var selectedBeacon: BeaconInfo? = null
    private var isBeaconActive = false
    
    // Service binding
    private var advertiseService: BeaconAdvertiseService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BeaconAdvertiseService.LocalBinder
            advertiseService = binder.getService()
            advertiseService?.setServiceListener(this@BeaconActivity)
            isServiceBound = true
            
            // Check if service is already advertising
            if (advertiseService?.isAdvertising() == true) {
                isBeaconActive = true
                updateUI()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            advertiseService = null
            isServiceBound = false
        }
    }
    
    // Permission request
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startBeacon()
        } else {
            Toast.makeText(this, "Izin Bluetooth diperlukan untuk beacon", Toast.LENGTH_LONG).show()
        }
    }
    
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(this, "Bluetooth harus diaktifkan untuk beacon", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_beacon)

        beaconRepository = BeaconRepository(this)
        beacons = beaconRepository.getBeacons()

        initViews()
        setupListeners()
        setupSpinner()
        updateUI()
        
        // Bind to service
        bindAdvertiseService()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        spinnerLocation = findViewById(R.id.spinnerLocation)
        viewStatusIndicator = findViewById(R.id.viewStatusIndicator)
        tvStatus = findViewById(R.id.tvStatus)
        tvBroadcastLocation = findViewById(R.id.tvBroadcastLocation)
        btnToggleBeacon = findViewById(R.id.btnToggleBeacon)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnToggleBeacon.setOnClickListener {
            toggleBeacon()
        }
    }

    private fun setupSpinner() {
        val locationNames = beacons.map { it.location }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            locationNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinnerLocation.adapter = adapter
        spinnerLocation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBeacon = beacons.getOrNull(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedBeacon = null
            }
        }
    }
    
    private fun bindAdvertiseService() {
        val intent = Intent(this, BeaconAdvertiseService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun toggleBeacon() {
        if (isBeaconActive) {
            stopBeacon()
        } else {
            checkPermissionsAndStart()
        }
    }
    
    private fun checkPermissionsAndStart() {
        // Check if Bluetooth is enabled
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
            return
        }
        
        // Check permissions
        val requiredPermissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        
        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            startBeacon()
        }
    }

    private fun startBeacon() {
        val beacon = selectedBeacon ?: return
        
        // Check if advertising is supported
        if (advertiseService?.isAdvertisingSupported() == false) {
            Toast.makeText(this, "Perangkat tidak mendukung BLE Advertising", Toast.LENGTH_LONG).show()
            return
        }
        
        // Start service with beacon info
        val intent = Intent(this, BeaconAdvertiseService::class.java).apply {
            action = BeaconAdvertiseService.ACTION_START_BEACON
            putExtra(BeaconAdvertiseService.EXTRA_BEACON_UUID, beacon.uuid)
            putExtra(BeaconAdvertiseService.EXTRA_BEACON_MAJOR, beacon.major)
            putExtra(BeaconAdvertiseService.EXTRA_BEACON_MINOR, beacon.minor)
            putExtra(BeaconAdvertiseService.EXTRA_BEACON_LOCATION, beacon.location)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBeacon() {
        val intent = Intent(this, BeaconAdvertiseService::class.java).apply {
            action = BeaconAdvertiseService.ACTION_STOP_BEACON
        }
        startService(intent)
    }

    private fun updateUI() {
        if (isBeaconActive) {
            viewStatusIndicator.setBackgroundColor(
                ContextCompat.getColor(this, R.color.status_active)
            )
            tvStatus.text = getString(R.string.beacon_active)
            tvBroadcastLocation.visibility = View.VISIBLE
            tvBroadcastLocation.text = selectedBeacon?.location ?: ""
            btnToggleBeacon.text = getString(R.string.stop_beacon)
            btnToggleBeacon.setBackgroundColor(
                ContextCompat.getColor(this, R.color.button_danger)
            )
            spinnerLocation.isEnabled = false
        } else {
            viewStatusIndicator.setBackgroundColor(
                ContextCompat.getColor(this, R.color.status_inactive)
            )
            tvStatus.text = getString(R.string.beacon_inactive)
            tvBroadcastLocation.visibility = View.GONE
            btnToggleBeacon.text = getString(R.string.start_beacon)
            btnToggleBeacon.setBackgroundColor(
                ContextCompat.getColor(this, R.color.button_primary)
            )
            spinnerLocation.isEnabled = true
        }
    }
    
    // BeaconAdvertiseService.ServiceListener implementation
    
    override fun onBeaconStarted() {
        runOnUiThread {
            isBeaconActive = true
            updateUI()
            Toast.makeText(this, "Beacon aktif", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onBeaconStopped() {
        runOnUiThread {
            isBeaconActive = false
            updateUI()
        }
    }
    
    override fun onBeaconError(errorMessage: String) {
        runOnUiThread {
            isBeaconActive = false
            updateUI()
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            advertiseService?.setServiceListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
