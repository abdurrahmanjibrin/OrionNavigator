package com.orion.app.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.orion.app.R
import com.orion.app.data.LiveLocationRepository
import com.orion.app.data.SafeZone
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * Safe Zones management screen for Pendamping
 * Allows adding, viewing, and deleting geofence zones
 */
class SafeZonesActivity : AppCompatActivity() {
    
    private lateinit var mapView: MapView
    private lateinit var recyclerZones: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var btnAddZone: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var tvInstruction: TextView
    
    private val repository = LiveLocationRepository()
    private var childId: String? = null
    private var zones = mutableListOf<SafeZone>()
    private var zoneOverlays = mutableMapOf<String, Polygon>()
    private var zoneMarkers = mutableMapOf<String, Marker>()
    
    private var isAddingZone = false
    private var selectedLocation: GeoPoint? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_safe_zones)
        
        childId = intent.getStringExtra("childId")
        if (childId == null) {
            Toast.makeText(this, "Error: Child ID not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        initViews()
        setupMap()
        setupListeners()
        loadZones()
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    
    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        recyclerZones = findViewById(R.id.recyclerZones)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        btnAddZone = findViewById(R.id.btnAddZone)
        btnBack = findViewById(R.id.btnBack)
        tvInstruction = findViewById(R.id.tvInstruction)
        
        recyclerZones.layoutManager = LinearLayoutManager(this)
    }
    
    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(-7.28, 112.75))
        
        // Add tap listener for adding zones
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (isAddingZone && p != null) {
                    selectedLocation = p
                    showAddZoneDialog(p)
                }
                return true
            }
            
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    selectedLocation = p
                    showAddZoneDialog(p)
                }
                return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(mapEventsReceiver))
    }
    
    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }
        
        btnAddZone.setOnClickListener {
            isAddingZone = true
            tvInstruction.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.tap_to_add_zone), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun loadZones() {
        childId?.let { cid ->
            lifecycleScope.launch {
                repository.observeSafeZones(cid).collectLatest { zoneList ->
                    zones.clear()
                    zones.addAll(zoneList)
                    updateUI()
                    updateMapOverlays()
                }
            }
        }
    }
    
    private fun updateUI() {
        if (zones.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            recyclerZones.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            recyclerZones.visibility = View.VISIBLE
            recyclerZones.adapter = ZoneAdapter(zones)
        }
    }
    
    private fun updateMapOverlays() {
        // Clear existing overlays (except map events)
        zoneOverlays.values.forEach { mapView.overlays.remove(it) }
        zoneMarkers.values.forEach { mapView.overlays.remove(it) }
        zoneOverlays.clear()
        zoneMarkers.clear()
        
        // Add overlays for each zone
        zones.forEach { zone ->
            if (zone.isActive) {
                // Circle polygon
                val circle = Polygon(mapView)
                circle.points = Polygon.pointsAsCircle(
                    GeoPoint(zone.latitude, zone.longitude),
                    zone.radiusMeters.toDouble()
                )
                circle.fillPaint.color = Color.argb(50, 76, 175, 80) // Semi-transparent green
                circle.outlinePaint.color = ContextCompat.getColor(this, R.color.accent)
                circle.outlinePaint.strokeWidth = 3f
                circle.title = zone.name
                
                mapView.overlays.add(circle)
                zoneOverlays[zone.id] = circle
                
                // Center marker
                val marker = Marker(mapView).apply {
                    position = GeoPoint(zone.latitude, zone.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = zone.name
                    snippet = "${zone.radiusMeters.toInt()}m"
                    icon = ContextCompat.getDrawable(this@SafeZonesActivity, R.drawable.ic_location_modern)
                }
                mapView.overlays.add(marker)
                zoneMarkers[zone.id] = marker
            }
        }
        
        mapView.invalidate()
        
        // Center on first zone if available
        if (zones.isNotEmpty()) {
            val first = zones.first()
            mapView.controller.animateTo(GeoPoint(first.latitude, first.longitude))
        }
    }
    
    private fun showAddZoneDialog(location: GeoPoint) {
        isAddingZone = false
        tvInstruction.visibility = View.GONE
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_zone, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etZoneName)
        val sliderRadius = dialogView.findViewById<Slider>(R.id.sliderRadius)
        val tvRadiusValue = dialogView.findViewById<TextView>(R.id.tvRadiusValue)
        val checkExit = dialogView.findViewById<MaterialCheckBox>(R.id.checkAlertExit)
        val checkEnter = dialogView.findViewById<MaterialCheckBox>(R.id.checkAlertEnter)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSave)
        
        sliderRadius.addOnChangeListener { _, value, _ ->
            tvRadiusValue.text = "${value.toInt()}m"
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                etName.error = getString(R.string.name_required)
                return@setOnClickListener
            }
            
            val zone = SafeZone(
                name = name,
                latitude = location.latitude,
                longitude = location.longitude,
                radiusMeters = sliderRadius.value,
                alertOnExit = checkExit.isChecked,
                alertOnEnter = checkEnter.isChecked
            )
            
            saveZone(zone)
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun saveZone(zone: SafeZone) {
        childId?.let { cid ->
            lifecycleScope.launch {
                try {
                    repository.addSafeZone(cid, zone)
                    Toast.makeText(this@SafeZonesActivity, 
                        getString(R.string.zone_saved), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SafeZonesActivity, 
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun deleteZone(zone: SafeZone) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_zone))
            .setMessage(getString(R.string.delete_zone_confirm, zone.name))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                childId?.let { cid ->
                    lifecycleScope.launch {
                        try {
                            repository.deleteSafeZone(cid, zone.id)
                        } catch (e: Exception) {
                            Toast.makeText(this@SafeZonesActivity, 
                                "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }
    
    private fun toggleZone(zone: SafeZone, isActive: Boolean) {
        childId?.let { cid ->
            lifecycleScope.launch {
                try {
                    repository.toggleSafeZoneActive(cid, zone.id, isActive)
                } catch (e: Exception) {
                    Toast.makeText(this@SafeZonesActivity, 
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // RecyclerView Adapter
    private inner class ZoneAdapter(private val items: List<SafeZone>) : 
        RecyclerView.Adapter<ZoneAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvZoneName)
            val tvRadius: TextView = view.findViewById(R.id.tvZoneRadius)
            val tvAlertExit: TextView = view.findViewById(R.id.tvAlertExit)
            val tvAlertEnter: TextView = view.findViewById(R.id.tvAlertEnter)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switchActive)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
            val viewIndicator: View = view.findViewById(R.id.viewZoneIndicator)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_zone, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val zone = items[position]
            
            holder.tvName.text = zone.name
            holder.tvRadius.text = getString(R.string.radius_format, zone.radiusMeters.toInt())
            
            holder.tvAlertExit.visibility = if (zone.alertOnExit) View.VISIBLE else View.GONE
            holder.tvAlertEnter.visibility = if (zone.alertOnEnter) View.VISIBLE else View.GONE
            
            holder.switchActive.isChecked = zone.isActive
            holder.viewIndicator.setBackgroundResource(
                if (zone.isActive) R.drawable.bg_pulse_active else R.drawable.bg_pulse_inactive
            )
            
            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                toggleZone(zone, isChecked)
            }
            
            holder.btnDelete.setOnClickListener {
                deleteZone(zone)
            }
            
            holder.itemView.setOnClickListener {
                // Center map on this zone
                mapView.controller.animateTo(GeoPoint(zone.latitude, zone.longitude))
            }
        }
        
        override fun getItemCount() = items.size
    }
}
