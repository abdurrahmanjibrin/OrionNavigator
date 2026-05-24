package com.orion.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Vibrator
import android.preference.PreferenceManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
 * Pendamping (Caregiver) Dashboard
 * Features: Enter code to connect, map view for tracking, disconnect, logout
 * Accessible with TTS announcements
 */
class PendampingActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var mapView: MapView
    private lateinit var tvUserName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var viewConnectionStatus: View
    private lateinit var viewPulseRing: View
    private lateinit var layoutOverlay: FrameLayout
    private lateinit var tvOverlayMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutEnterCode: LinearLayout
    private lateinit var layoutConnected: LinearLayout
    private lateinit var layoutActions: LinearLayout
    private lateinit var etLinkCode: TextInputEditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnCenterMap: MaterialButton
    private lateinit var btnSafeZones: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var btnLogout: MaterialButton
    
    private val repository = LiveLocationRepository()
    private var userId: String? = null
    private var linkedChildId: String? = null
    private var locationMarker: Marker? = null
    private var lastKnownLocation: GeoPoint? = null
    
    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_pendamping)
        
        initViews()
        setupMap()
        setupListeners()
        initTts()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
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
    
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
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
        layoutEnterCode = findViewById(R.id.layoutEnterCode)
        layoutConnected = findViewById(R.id.layoutConnected)
        layoutActions = findViewById(R.id.layoutActions)
        etLinkCode = findViewById(R.id.etLinkCode)
        btnConnect = findViewById(R.id.btnConnect)
        btnCenterMap = findViewById(R.id.btnCenterMap)
        btnSafeZones = findViewById(R.id.btnSafeZones)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnLogout = findViewById(R.id.btnLogout)
    }
    
    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(-7.28, 112.75))
    }
    
    private fun initTts() {
        tts = TextToSpeech(this, this)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
            
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "welcome" && linkedChildId == null) {
                        runOnUiThread {
                            tts?.speak(
                                "Masukkan kode 6 digit yang diberikan oleh pengguna tunanetra untuk terhubung.",
                                TextToSpeech.QUEUE_ADD,
                                null,
                                "instruction"
                            )
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
            
            tts?.speak(
                "Halaman pendamping. Orion Navigator.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "welcome"
            )
        }
    }
    
    private fun vibrate() {
        vibrator?.vibrate(50)
    }
    
    private fun speakAndVibrate(text: String) {
        vibrator?.vibrate(100)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "feedback")
    }
    
    private fun setupListeners() {
        btnConnect.setOnClickListener {
            vibrate()
            val code = etLinkCode.text?.toString()?.trim()
            if (code?.length == 6) {
                connectWithCode(code)
            } else {
                speakAndVibrate(getString(R.string.invalid_code))
            }
        }
        
        btnCenterMap.setOnClickListener {
            vibrate()
            lastKnownLocation?.let { location ->
                mapView.controller.animateTo(location)
                speakAndVibrate("Peta dipusatkan ke lokasi pengguna")
            }
        }
        
        btnSafeZones.setOnClickListener {
            vibrate()
            openSafeZones()
        }
        
        btnDisconnect.setOnClickListener {
            vibrate()
            disconnectChild()
        }
        
        btnLogout.setOnClickListener {
            vibrate()
            showLogoutDialog()
        }
    }
    
    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                userId = repository.getCurrentUserId()
                linkedChildId = repository.getLinkedChildId(userId!!)
                
                if (linkedChildId != null) {
                    showConnectedUI()
                    startObservingLocation()
                } else {
                    showEnterCodeUI()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                speakAndVibrate("Terjadi kesalahan koneksi")
            }
        }
    }
    
    private fun showEnterCodeUI() {
        layoutEnterCode.visibility = View.VISIBLE
        layoutConnected.visibility = View.GONE
        layoutActions.visibility = View.GONE
        layoutOverlay.visibility = View.GONE
    }
    
    private fun showConnectedUI() {
        layoutEnterCode.visibility = View.GONE
        layoutConnected.visibility = View.VISIBLE
        layoutActions.visibility = View.VISIBLE
    }
    
    private fun connectWithCode(code: String) {
        btnConnect.isEnabled = false
        speakAndVibrate("Menghubungkan dengan kode $code")
        
        lifecycleScope.launch {
            try {
                val childId = repository.findChildByLinkCode(code)
                
                if (childId != null) {
                    repository.linkParent(childId, userId!!)
                    linkedChildId = childId
                    
                    runOnUiThread {
                        showConnectedUI()
                        speakAndVibrate("Berhasil terhubung! Memuat lokasi.")
                        startObservingLocation()
                    }
                } else {
                    runOnUiThread {
                        speakAndVibrate(getString(R.string.invalid_code))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    speakAndVibrate("Gagal terhubung. Periksa koneksi internet.")
                }
            } finally {
                runOnUiThread {
                    btnConnect.isEnabled = true
                }
            }
        }
    }
    
    private fun startObservingLocation() {
        layoutOverlay.visibility = View.VISIBLE
        tvOverlayMessage.text = getString(R.string.waiting_location)
        
        linkedChildId?.let { childId ->
            lifecycleScope.launch {
                repository.observeLocation(childId).collectLatest { locationData ->
                    if (locationData != null) {
                        updateMapWithLocation(locationData)
                    } else {
                        showNoLocation()
                    }
                }
            }
        }
    }
    
    private fun updateMapWithLocation(location: LocationData) {
        runOnUiThread {
            layoutOverlay.visibility = View.GONE
            
            val geoPoint = GeoPoint(location.lat, location.lng)
            lastKnownLocation = geoPoint
            
            if (locationMarker == null) {
                locationMarker = Marker(mapView).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = getString(R.string.viewing_location)
                    icon = ContextCompat.getDrawable(this@PendampingActivity, R.drawable.ic_location_modern)
                }
                mapView.overlays.add(locationMarker)
            }
            
            locationMarker?.position = geoPoint
            mapView.controller.animateTo(geoPoint)
            mapView.invalidate()
            
            if (location.isActive) {
                tvStatus.text = getString(R.string.live_location_active)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.glow_green))
                viewConnectionStatus.setBackgroundResource(R.drawable.bg_pulse_active)
                startPulseAnimation()
            } else {
                tvStatus.text = getString(R.string.child_offline)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
                viewConnectionStatus.setBackgroundResource(R.drawable.bg_pulse_inactive)
                stopPulseAnimation()
            }
            
            tvLastUpdate.text = "${getString(R.string.last_updated)}: ${getTimeAgo(location.updatedAt)}"
        }
    }
    
    private fun startPulseAnimation() {
        try {
            viewPulseRing.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_animation))
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
            speakAndVibrate("Lokasi pengguna tidak tersedia saat ini")
        }
    }
    
    private fun disconnectChild() {
        lifecycleScope.launch {
            try {
                linkedChildId?.let { childId ->
                    repository.unlinkParent(childId)
                    linkedChildId = null
                    
                    runOnUiThread {
                        locationMarker?.let { mapView.overlays.remove(it) }
                        locationMarker = null
                        lastKnownLocation = null
                        etLinkCode.text?.clear()
                        showEnterCodeUI()
                        speakAndVibrate("Berhasil memutuskan hubungan")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    speakAndVibrate("Gagal memutuskan hubungan")
                }
            }
        }
    }
    
    private fun showLogoutDialog() {
        tts?.speak(getString(R.string.logout_confirm), TextToSpeech.QUEUE_FLUSH, null, "confirm")
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logout))
            .setMessage(getString(R.string.logout_confirm))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                logout()
            }
            .setNegativeButton(getString(R.string.no)) { dialog, _ ->
                dialog.dismiss()
                tts?.speak("Dibatalkan", TextToSpeech.QUEUE_FLUSH, null, "cancel")
            }
            .show()
    }
    
    private fun logout() {
        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(SplashActivity.KEY_USER_ROLE).apply()
        
        speakAndVibrate(getString(R.string.role_changed))
        
        android.os.Handler(mainLooper).postDelayed({
            val intent = Intent(this, RoleSelectionActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }, 1500)
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
    
    private fun openSafeZones() {
        linkedChildId?.let { childId ->
            val intent = Intent(this, SafeZonesActivity::class.java)
            intent.putExtra("childId", childId)
            startActivity(intent)
            speakAndVibrate("Membuka zona aman")
        }
    }
}
