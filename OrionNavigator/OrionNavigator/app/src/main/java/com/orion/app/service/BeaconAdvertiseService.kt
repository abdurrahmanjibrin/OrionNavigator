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
import com.orion.app.ble.BleAdvertiser
import com.orion.app.data.BeaconInfo
import com.orion.app.ui.ModeSelectionActivity

/**
 * Foreground service for BLE advertising (phone-as-beacon mode)
 * Keeps beacon broadcasting active even when screen is off
 */
class BeaconAdvertiseService : Service(), BleAdvertiser.AdvertiseListener {

    companion object {
        private const val CHANNEL_ID = "orion_beacon_channel"
        private const val NOTIFICATION_ID = 1002
        
        const val ACTION_START_BEACON = "com.orion.app.START_BEACON"
        const val ACTION_STOP_BEACON = "com.orion.app.STOP_BEACON"
        
        const val EXTRA_BEACON_UUID = "beacon_uuid"
        const val EXTRA_BEACON_MAJOR = "beacon_major"
        const val EXTRA_BEACON_MINOR = "beacon_minor"
        const val EXTRA_BEACON_LOCATION = "beacon_location"
    }

    private val binder = LocalBinder()
    private lateinit var bleAdvertiser: BleAdvertiser
    private var serviceListener: ServiceListener? = null
    private var currentLocation: String = ""

    inner class LocalBinder : Binder() {
        fun getService(): BeaconAdvertiseService = this@BeaconAdvertiseService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleAdvertiser = BleAdvertiser(this)
        bleAdvertiser.setAdvertiseListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BEACON -> {
                val uuid = intent.getStringExtra(EXTRA_BEACON_UUID) ?: return START_NOT_STICKY
                val major = intent.getIntExtra(EXTRA_BEACON_MAJOR, 0)
                val minor = intent.getIntExtra(EXTRA_BEACON_MINOR, 0)
                currentLocation = intent.getStringExtra(EXTRA_BEACON_LOCATION) ?: ""
                
                startForegroundBeacon(uuid, major, minor)
            }
            ACTION_STOP_BEACON -> {
                stopBeacon()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setServiceListener(listener: ServiceListener?) {
        serviceListener = listener
    }

    fun startBeacon(beacon: BeaconInfo) {
        currentLocation = beacon.location
        startForegroundBeacon(beacon.uuid, beacon.major, beacon.minor)
    }

    private fun startForegroundBeacon(uuid: String, major: Int, minor: Int) {
        val notification = createNotification("Menyiapkan beacon...")
        startForeground(NOTIFICATION_ID, notification)
        bleAdvertiser.startAdvertising(uuid, major, minor)
    }

    fun stopBeacon() {
        bleAdvertiser.stopAdvertising()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isAdvertising(): Boolean = bleAdvertiser.isAdvertising()

    fun isAdvertisingSupported(): Boolean = bleAdvertiser.isAdvertisingSupported()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Beacon Orion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi beacon aktif"
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

        val stopIntent = Intent(this, BeaconAdvertiseService::class.java).apply {
            action = ACTION_STOP_BEACON
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orion Beacon")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_beacon)
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

    // BleAdvertiser.AdvertiseListener implementation

    override fun onAdvertiseStarted() {
        updateNotification("Beacon aktif: $currentLocation")
        serviceListener?.onBeaconStarted()
    }

    override fun onAdvertiseStopped() {
        serviceListener?.onBeaconStopped()
    }

    override fun onAdvertiseFailed(errorMessage: String) {
        serviceListener?.onBeaconError(errorMessage)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        bleAdvertiser.stopAdvertising()
        super.onDestroy()
    }

    /**
     * Listener interface for service events
     */
    interface ServiceListener {
        fun onBeaconStarted()
        fun onBeaconStopped()
        fun onBeaconError(errorMessage: String)
    }
}
