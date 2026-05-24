package com.orion.app.data

import android.content.Context
import com.google.gson.Gson
import com.orion.app.R
import java.io.InputStreamReader

/**
 * Repository for accessing beacon configuration data
 */
class BeaconRepository(private val context: Context) {

    private val gson = Gson()
    private var cachedBeacons: List<BeaconInfo>? = null

    /**
     * Get all configured beacons from JSON file
     */
    fun getBeacons(): List<BeaconInfo> {
        cachedBeacons?.let { return it }

        return try {
            val inputStream = context.resources.openRawResource(R.raw.beacons)
            val reader = InputStreamReader(inputStream)
            val config = gson.fromJson(reader, BeaconConfig::class.java)
            reader.close()
            
            val beacons = config.beacons.map { it.toBeaconInfo() }
            cachedBeacons = beacons
            beacons
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Find beacon by UUID, major, and minor
     */
    fun findBeacon(uuid: String, major: Int, minor: Int): BeaconInfo? {
        return getBeacons().find {
            it.uuid.equals(uuid, ignoreCase = true) &&
            it.major == major &&
            it.minor == minor
        }
    }

    /**
     * Find beacon by location name
     */
    fun findBeaconByLocation(location: String): BeaconInfo? {
        return getBeacons().find {
            it.location.equals(location, ignoreCase = true)
        }
    }
}
