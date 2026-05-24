package com.orion.app.ui

import android.animation.ValueAnimator
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.orion.app.R
import com.orion.app.data.LiveLocationRepository
import com.orion.app.data.LocationData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

/**
 * Live Location screen for Parent (caregiver)
 * Features: OSM Map, real-time location updates, connection status, modern UI
 */
class LiveLocationParentActivity : AppCompatActivity() {
    
    private lateinit var mapView: MapView
    private lateinit var tvUserName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var viewConnectionStatus: View
    private lateinit var viewPulseRing: View
    private lateinit var layoutOverlay: FrameLayout
    private lateinit var tvOverlayMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cardEnterCode: CardView
    private lateinit var etLinkCode: TextInputEditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnCenterMap: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var viewLoadingRing1: View
    
    private val repository = LiveLocationRepository()
    private var userId: String? = null
    private var linkedChildId: String? = null
    private var locationMarker: Marker? = null
    private var lastKnownLocation: GeoPoint? = null
    private var markerAnimator: ValueAnimator? = null
    private var childIsActive: Boolean = false
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize OSMDroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_live_location_parent)
        
        initViews()
        setupMap()
        setupListeners()
        loadUserData()
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
        tvUserName = findViewById(R.id.tvUserName)
        tvStatus = findViewById(R.id.tvStatus)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        viewConnectionStatus = findViewById(R.id.viewConnectionStatus)
        viewPulseRing = findViewById(R.id.viewPulseRing)
        layoutOverlay = findViewById(R.id.layoutOverlay)
        tvOverlayMessage = findViewById(R.id.tvOverlayMessage)
        progressBar = findViewById(R.id.progressBar)
        cardEnterCode = findViewById(R.id.cardEnterCode)
        etLinkCode = findViewById(R.id.etLinkCode)
        btnConnect = findViewById(R.id.btnConnect)
        btnCenterMap = findViewById(R.id.btnCenterMap)
        btnRefresh = findViewById(R.id.btnRefresh)
        viewLoadingRing1 = findViewById(R.id.viewLoadingRing1)
    }
    
    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        
        // Default center (Indonesia)
        mapView.controller.setCenter(GeoPoint(-7.28, 112.75))
    }
    
    private fun setupListeners() {
        btnConnect.setOnClickListener {
            val code = etLinkCode.text?.toString()?.trim()
            if (code?.length == 6) {
                connectWithCode(code)
            } else {
                Toast.makeText(this, R.string.invalid_code, Toast.LENGTH_SHORT).show()
            }
        }
        
        btnCenterMap.setOnClickListener {
            lastKnownLocation?.let { location ->
                mapView.controller.animateTo(location)
            }
        }
        
        btnRefresh.setOnClickListener {
            linkedChildId?.let { startObservingLocation() }
        }
    }
    
    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                userId = repository.getCurrentUserId()
                
                // Check for linked child
                linkedChildId = repository.getLinkedChildId(userId!!)
                
                if (linkedChildId != null) {
                    // Already linked, start observing
                    cardEnterCode.visibility = View.GONE
                    startObservingLocation()
                } else {
                    // Show link code entry
                    cardEnterCode.visibility = View.VISIBLE
                    layoutOverlay.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showError("Connection error")
            }
        }
    }
    
    private fun connectWithCode(code: String) {
        btnConnect.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val childId = repository.findChildByLinkCode(code)
                
                if (childId != null) {
                    repository.linkParent(childId, userId!!)
                    linkedChildId = childId
                    cardEnterCode.visibility = View.GONE
                    startObservingLocation()
                    Toast.makeText(this@LiveLocationParentActivity, R.string.tts_linked_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@LiveLocationParentActivity, R.string.invalid_code, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@LiveLocationParentActivity, R.string.invalid_code, Toast.LENGTH_SHORT).show()
            } finally {
                btnConnect.isEnabled = true
            }
        }
    }
    
    private fun startObservingLocation() {
        layoutOverlay.visibility = View.VISIBLE
        tvOverlayMessage.text = getString(R.string.waiting_location)
        
        // Start loading animation
        startLoadingAnimation()
        
        linkedChildId?.let { childId ->
            // Observe full location data (lat, lng, etc.)
            lifecycleScope.launch {
                repository.observeLocation(childId).collectLatest { locationData ->
                    if (locationData != null) {
                        updateMapWithLocation(locationData)
                    } else {
                        showNoLocation()
                    }
                }
            }
            
            // Observe isActive status separately to catch immediate status changes
            // This fixes the race condition where the service has started (isActive=true)
            // but GPS hasn't fired yet, so LocationData still has the old isActive value.
            lifecycleScope.launch {
                repository.observeLocationActive(childId).collectLatest { isActive ->
                    childIsActive = isActive
                    runOnUiThread { updateStatusDisplay(isActive) }
                }
            }
        }
    }
    
    private fun startLoadingAnimation() {
        try {
            val pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse_animation)
            viewLoadingRing1.startAnimation(pulseAnim)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun stopLoadingAnimation() {
        viewLoadingRing1.clearAnimation()
    }
    
    private fun updateMapWithLocation(location: LocationData) {
        runOnUiThread {
            layoutOverlay.visibility = View.GONE
            stopLoadingAnimation()
            
            val geoPoint = GeoPoint(location.lat, location.lng)
            
            // Update or create marker
            if (locationMarker == null) {
                locationMarker = Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = getString(R.string.viewing_location)
                    icon = ContextCompat.getDrawable(this@LiveLocationParentActivity, R.drawable.ic_location_modern)
                }
                mapView.overlays.add(locationMarker)
                // First position — set directly, no animation
                locationMarker?.position = geoPoint
                mapView.controller.animateTo(geoPoint)
            } else {
                // Subsequent positions — animate smoothly
                locationMarker?.let { animateMarkerTo(it, geoPoint) }
                mapView.controller.animateTo(geoPoint, mapView.zoomLevelDouble, 1000L)
            }
            
            lastKnownLocation = geoPoint
            mapView.invalidate()
            
            // Use the dedicated childIsActive field which is updated by a separate listener.
            // The LocationData.isActive may be stale during race conditions.
            updateStatusDisplay(childIsActive)
            
            // Update timestamp + accuracy indicator
            val timeAgo = getTimeAgo(location.updatedAt)
            val accuracyText = if (location.accuracy > 0f) " · ±${location.accuracy.toInt()}m" else ""
            tvLastUpdate.text = "${getString(R.string.last_updated)}: $timeAgo$accuracyText"
        }
    }
    
    /**
     * Update the connection status display based on the child's isActive flag.
     * Called both from the location observer and the dedicated isActive observer.
     */
    private fun updateStatusDisplay(isActive: Boolean) {
        if (isActive) {
            tvStatus.text = getString(R.string.live_location_active)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.glow_green))
            viewConnectionStatus.setBackgroundResource(R.drawable.bg_pulse_active)
            viewPulseRing.visibility = View.VISIBLE
            startPulseAnimation()
        } else {
            tvStatus.text = getString(R.string.child_offline)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            viewConnectionStatus.setBackgroundResource(R.drawable.bg_pulse_inactive)
            viewPulseRing.visibility = View.GONE
            stopPulseAnimation()
        }
    }
    
    /**
     * Smoothly animates a marker from its current position to a new position
     * over 1 second using linear interpolation, preventing the "jumpy" appearance.
     */
    private fun animateMarkerTo(marker: Marker, newPosition: GeoPoint) {
        markerAnimator?.cancel()
        
        val startPosition = marker.position ?: newPosition
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedFraction.toDouble()
                val lat = startPosition.latitude + (newPosition.latitude - startPosition.latitude) * t
                val lng = startPosition.longitude + (newPosition.longitude - startPosition.longitude) * t
                marker.position = GeoPoint(lat, lng)
                mapView.invalidate()
            }
        }
        markerAnimator?.start()
    }
    
    private fun startPulseAnimation() {
        try {
            val pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse_animation)
            viewPulseRing.startAnimation(pulseAnim)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun stopPulseAnimation() {
        viewPulseRing.clearAnimation()
    }
    
    private fun showNoLocation() {
        runOnUiThread {
            layoutOverlay.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            tvOverlayMessage.text = getString(R.string.location_unavailable)
            stopLoadingAnimation()
        }
    }
    
    private fun showError(message: String) {
        runOnUiThread {
            layoutOverlay.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            tvOverlayMessage.text = message
            stopLoadingAnimation()
        }
    }
    
    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "Baru saja"
            diff < 3600_000 -> "${diff / 60_000} menit lalu"
            diff < 86400_000 -> "${diff / 3600_000} jam lalu"
            else -> dateFormat.format(Date(timestamp))
        }
    }
}
