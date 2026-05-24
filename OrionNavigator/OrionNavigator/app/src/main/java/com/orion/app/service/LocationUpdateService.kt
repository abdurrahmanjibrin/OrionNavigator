package com.orion.app.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.orion.app.R
import com.orion.app.data.LiveLocationRepository
import com.orion.app.data.SafeZone
import com.orion.app.ui.TunanetraActivity
import kotlinx.coroutines.*
import java.util.*

/**
 * Foreground service for tracking and uploading location updates.
 * 
 * Key design decisions:
 * - Uses PRIORITY_HIGH_ACCURACY (GPS) for precise tracking
 * - 10s update interval, 5m min displacement for responsive small-movement tracking
 * - Filters out inaccurate fixes (>25m accuracy) to prevent jumpy updates
 * - Survives app close via onTaskRemoved() restart mechanism
 * - Uploads accuracy/speed/bearing alongside lat/lng for richer caregiver view
 */
class LocationUpdateService : Service(), TextToSpeech.OnInitListener {
    
    companion object {
        const val ACTION_START = "com.orion.app.action.START_LOCATION"
        const val ACTION_STOP = "com.orion.app.action.STOP_LOCATION"
        const val EXTRA_USER_ID = "extra_user_id"
        
        private const val NOTIFICATION_CHANNEL_ID = "live_location_channel"
        private const val GEOFENCE_CHANNEL_ID = "geofence_alert_channel"
        private const val NOTIFICATION_ID = 2001
        private const val GEOFENCE_NOTIFICATION_ID = 2002
        
        // Update interval: 10 seconds (was 30s — too slow for walking pace)
        private const val UPDATE_INTERVAL_MS = 10_000L
        // Fastest update: 5 seconds (was 15s)
        private const val FASTEST_INTERVAL_MS = 5_000L
        // Minimum displacement: 5 meters (was 20m — missed small movements)
        private const val MIN_DISPLACEMENT_M = 5f
        // Maximum acceptable accuracy: reject fixes worse than 25m
        private const val MAX_ACCEPTABLE_ACCURACY_M = 25f
        
        /**
         * Static flag so activities can instantly check if the service is running
         * without querying Firebase (which is async and unreliable for UI state).
         */
        @Volatile
        var isServiceRunning = false
            private set
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repository = LiveLocationRepository()
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    
    private var userId: String? = null
    private var isRunning = false
    // Tracks whether the service is *supposed* to be running.
    // Distinguishes intentional stop (user pressed stop) from system kill (app swiped away).
    private var shouldBeRunning = false
    
    // Geofence state tracking
    private var safeZones: List<SafeZone> = emptyList()
    private val zoneStates = mutableMapOf<String, Boolean>() // zoneId -> isInside
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var vibrator: Vibrator? = null
    
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannels()
        setupLocationCallback()
        
        // Initialize TTS for geofence alerts
        tts = TextToSpeech(this, this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
            ttsReady = true
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                userId = intent.getStringExtra(EXTRA_USER_ID)
                if (userId != null && !isRunning) {
                    startLocationUpdates()
                    loadSafeZones()
                }
            }
            ACTION_STOP -> {
                shouldBeRunning = false
                stopLocationUpdates()
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Called when user swipes the app away from recents.
     * If live location should still be running, schedule a restart
     * so the service survives app close (like WhatsApp's live location).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (shouldBeRunning && userId != null) {
            val restartIntent = Intent(this, LocationUpdateService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USER_ID, userId)
            }
            val pendingIntent = PendingIntent.getService(
                this, 0, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (!shouldBeRunning) {
            // Intentional stop — mark inactive in Firebase
            stopLocationUpdates()
        } else {
            // System killed us (e.g., app swipe) — just remove callback,
            // do NOT mark inactive. onTaskRemoved will restart us.
            if (isRunning) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                isRunning = false
            }
        }
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Live Location",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracking your location for caregivers"
                setShowBadge(false)
            }
            
            val geofenceChannel = NotificationChannel(
                GEOFENCE_CHANNEL_ID,
                "Zone Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when entering or leaving safe zones"
                enableVibration(true)
            }
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(locationChannel)
            manager.createNotificationChannel(geofenceChannel)
        }
    }
    
    private fun buildNotification(): Notification {
        val intent = Intent(this, TunanetraActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, LocationUpdateService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.live_location_active))
            .setContentText(getString(R.string.live_location_description))
            .setSmallIcon(R.drawable.ic_location)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun loadSafeZones() {
        userId?.let { uid ->
            serviceScope.launch {
                try {
                    safeZones = repository.getSafeZones(uid)
                    // Initialize zone states (assume inside if we don't know yet)
                    safeZones.forEach { zone ->
                        if (!zoneStates.containsKey(zone.id)) {
                            zoneStates[zone.id] = false // Assume outside initially
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                
                // Filter out inaccurate fixes to prevent jumpy updates
                if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTABLE_ACCURACY_M) {
                    return
                }
                
                // Upload location to Firebase
                serviceScope.launch {
                    try {
                        userId?.let { uid ->
                            repository.updateLocation(
                                uid,
                                location.latitude,
                                location.longitude,
                                location.accuracy,
                                location.speed,
                                location.bearing
                            )
                            
                            // Check geofences
                            checkGeofences(location.latitude, location.longitude)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    
    private fun checkGeofences(lat: Double, lng: Double) {
        safeZones.filter { it.isActive }.forEach { zone ->
            val isInside = zone.contains(lat, lng)
            val wasInside = zoneStates[zone.id] ?: false
            
            if (isInside != wasInside) {
                // State changed
                zoneStates[zone.id] = isInside
                
                if (!isInside && zone.alertOnExit) {
                    // Exited zone
                    onZoneExit(zone)
                } else if (isInside && zone.alertOnEnter) {
                    // Entered zone
                    onZoneEnter(zone)
                }
            }
        }
    }
    
    private fun onZoneExit(zone: SafeZone) {
        // Haptic feedback
        vibrateAlert()
        
        // TTS announcement
        if (ttsReady) {
            tts?.speak(
                "Perhatian! Anda keluar dari zona ${zone.name}",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "zone_exit"
            )
        }
        
        // Show notification (for pendamping to see in their app)
        showGeofenceNotification(
            getString(R.string.zone_exit_title),
            getString(R.string.zone_exit_message, zone.name)
        )
        
        // Save event to Firebase for pendamping to see
        serviceScope.launch {
            try {
                userId?.let { uid ->
                    repository.saveGeofenceEvent(uid, zone.id, zone.name, "exit")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun onZoneEnter(zone: SafeZone) {
        // Haptic feedback
        vibrateAlert()
        
        // TTS announcement
        if (ttsReady) {
            tts?.speak(
                "Anda memasuki zona ${zone.name}",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "zone_enter"
            )
        }
        
        // Save event to Firebase
        serviceScope.launch {
            try {
                userId?.let { uid ->
                    repository.saveGeofenceEvent(uid, zone.id, zone.name, "enter")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun vibrateAlert() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 300, 200, 300), -1)
        }
    }
    
    private fun showGeofenceNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, GEOFENCE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(GEOFENCE_NOTIFICATION_ID, notification)
    }
    
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        
        // Use HIGH_ACCURACY (GPS) for precise tracking — was BALANCED (WiFi/Cell, ~40-100m)
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
        }.build()
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        shouldBeRunning = true
        isServiceRunning = true
        
        // Mark location as active in Firebase
        serviceScope.launch {
            try {
                userId?.let { uid ->
                    repository.setLocationActive(uid, true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun stopLocationUpdates() {
        if (isRunning) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isRunning = false
            isServiceRunning = false
            
            // Mark location as inactive in Firebase
            serviceScope.launch {
                try {
                    userId?.let { uid ->
                        repository.setLocationActive(uid, false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
