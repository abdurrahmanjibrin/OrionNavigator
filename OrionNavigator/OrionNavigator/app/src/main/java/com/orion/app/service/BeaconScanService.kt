package com.orion.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.orion.app.R
import com.orion.app.ble.DistanceCalculator
import com.orion.app.data.BeaconInfo
import com.orion.app.navigation.NavigationManager
import com.orion.app.ui.ModeSelectionActivity

/**
 * Foreground service for continuous BLE scanning during navigation
 * Keeps scanning active even when screen is off
 */
class BeaconScanService : Service(), NavigationManager.NavigationListener {

    companion object {
        private const val CHANNEL_ID = "orion_navigation_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_NAVIGATION = "com.orion.app.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "com.orion.app.STOP_NAVIGATION"
        
        const val EXTRA_BEACON_UUID = "beacon_uuid"
        const val EXTRA_BEACON_MAJOR = "beacon_major"
        const val EXTRA_BEACON_MINOR = "beacon_minor"
        const val EXTRA_BEACON_LOCATION = "beacon_location"
    }

    private val binder = LocalBinder()
    private lateinit var navigationManager: NavigationManager
    private var serviceListener: ServiceListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): BeaconScanService = this@BeaconScanService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        navigationManager = NavigationManager(this)
        navigationManager.initialize()
        navigationManager.setNavigationListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NAVIGATION -> {
                val uuid = intent.getStringExtra(EXTRA_BEACON_UUID) ?: return START_NOT_STICKY
                val major = intent.getIntExtra(EXTRA_BEACON_MAJOR, 0)
                val minor = intent.getIntExtra(EXTRA_BEACON_MINOR, 0)
                val location = intent.getStringExtra(EXTRA_BEACON_LOCATION) ?: ""
                
                val beacon = BeaconInfo(uuid, major, minor, location)
                startForegroundNavigation(beacon)
            }
            ACTION_STOP_NAVIGATION -> {
                stopNavigation()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setServiceListener(listener: ServiceListener?) {
        serviceListener = listener
    }

    fun startNavigation(beacon: BeaconInfo) {
        startForegroundNavigation(beacon)
    }

    private fun startForegroundNavigation(beacon: BeaconInfo) {
        val notification = createNotification("Menuju ke ${beacon.location}")
        startForeground(NOTIFICATION_ID, notification)
        navigationManager.startNavigation(beacon)
    }

    fun stopNavigation() {
        navigationManager.stopNavigation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isNavigating(): Boolean = navigationManager.isNavigating()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Navigasi Orion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi navigasi aktif"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, ModeSelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, BeaconScanService::class.java).apply {
            action = ACTION_STOP_NAVIGATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orion Navigator")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_check, "Berhenti", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // NavigationManager.NavigationListener implementation

    override fun onNavigationStarted(target: BeaconInfo) {
        serviceListener?.onNavigationStarted(target)
    }

    override fun onNavigationStopped() {
        serviceListener?.onNavigationStopped()
    }

    override fun onDistanceChanged(
        category: DistanceCalculator.DistanceCategory,
        distance: Double,
        guidance: String
    ) {
        // Update notification
        val statusText = when (category) {
            DistanceCalculator.DistanceCategory.VERY_CLOSE -> "Sangat dekat!"
            DistanceCalculator.DistanceCategory.CLOSE -> "Dekat"
            DistanceCalculator.DistanceCategory.MEDIUM -> "Sedang"
            DistanceCalculator.DistanceCategory.FAR -> "Jauh"
            DistanceCalculator.DistanceCategory.UNKNOWN -> "Mencari..."
        }
        updateNotification(statusText)
        
        serviceListener?.onDistanceChanged(category, distance, guidance)
    }

    override fun onNavigationError(errorMessage: String) {
        serviceListener?.onNavigationError(errorMessage)
    }

    override fun onDestroy() {
        navigationManager.destroy()
        super.onDestroy()
    }

    /**
     * Listener interface for service events
     */
    interface ServiceListener {
        fun onNavigationStarted(target: BeaconInfo)
        fun onNavigationStopped()
        fun onDistanceChanged(category: DistanceCalculator.DistanceCategory, distance: Double, guidance: String)
        fun onNavigationError(errorMessage: String)
    }
}
