package com.orion.app.navigation

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Beacon to Room Mapper
 * Decouples hardware beacon configuration (UUID, Major, Minor, Beacon Name)
 * from map GraphData node IDs so each can be edited independently.
 *
 * Reads configuration from assets/beacons_config.json (or files/beacons_config.json if present).
 */
object BeaconRoomMapper {

    private const val TAG = "BeaconRoomMapper"
    private const val CONFIG_FILE = "beacons_config.json"

    data class BeaconEntry(
        val major: Int,
        val minor: Int,
        val name: String,
        val nodeId: Int? = null,
        val targetNodeName: String? = null
    )

    /**
     * Active beacon UUID (loaded from beacons_config.json or map JSON)
     */
    var beaconUuid: String = "0112233445566778899AABBCCDDEEFF0"
        private set

    private var entriesMap: MutableMap<Pair<Int, Int>, BeaconEntry> = mutableMapOf()
    private var nodeToBeaconMap: MutableMap<Int, Pair<Int, Int>> = mutableMapOf()
    private var isConfigLoaded = false

    /**
     * Load decoupled beacon definitions from assets/beacons_config.json
     * (or internal files/beacons_config.json if customized).
     */
    fun loadFromConfigJson(context: Context, forceReload: Boolean = false) {
        if (isConfigLoaded && !forceReload) return

        try {
            val customFile = java.io.File(context.filesDir, CONFIG_FILE)
            val jsonString = if (customFile.exists() && customFile.length() > 0) {
                customFile.readText(Charsets.UTF_8)
            } else {
                context.assets.open(CONFIG_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val root = JSONObject(jsonString)
            val uuid = root.optString("uuid", root.optString("beaconUuid", "")).trim()
            if (uuid.isNotEmpty()) {
                beaconUuid = uuid
            }

            val beaconsArray = root.optJSONArray("beacons")
            val loadedEntries = mutableMapOf<Pair<Int, Int>, BeaconEntry>()
            if (beaconsArray != null) {
                for (i in 0 until beaconsArray.length()) {
                    val obj = beaconsArray.optJSONObject(i) ?: continue
                    val major = obj.optInt("major", -1)
                    val minor = obj.optInt("minor", -1)
                    if (major < 0 || minor < 0) continue

                    val name = obj.optString("beaconName", obj.optString("roomName", obj.optString("name", "Beacon $major:$minor"))).trim()
                    val nodeId = if (obj.has("nodeId") && !obj.isNull("nodeId")) obj.optInt("nodeId", -1).takeIf { it > 0 } else null
                    val targetNodeName = obj.optString("nodeName", obj.optString("roomName", "")).trim().takeIf { it.isNotEmpty() }

                    loadedEntries[Pair(major, minor)] = BeaconEntry(
                        major = major,
                        minor = minor,
                        name = name,
                        nodeId = nodeId,
                        targetNodeName = targetNodeName
                    )
                }
            }

            if (loadedEntries.isNotEmpty()) {
                entriesMap = loadedEntries
                rebuildReverseIndex()
                isConfigLoaded = true
                Log.i(TAG, "Loaded ${entriesMap.size} beacons from $CONFIG_FILE (UUID=$beaconUuid)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $CONFIG_FILE", e)
        }
    }

    private fun rebuildReverseIndex() {
        val reverse = mutableMapOf<Int, Pair<Int, Int>>()
        for ((key, entry) in entriesMap) {
            val resolvedNodeId = resolveNodeId(entry)
            if (resolvedNodeId != null) {
                reverse[resolvedNodeId] = key
            }
        }
        nodeToBeaconMap = reverse
    }

    private fun resolveNodeId(entry: BeaconEntry): Int? {
        // 1. Explicit nodeId (if valid in current GraphData)
        if (entry.nodeId != null && GraphData.getNode(entry.nodeId) != null) {
            return entry.nodeId
        }
        // 2. Match by targetNodeName or beacon name in current GraphData
        val candidateName = entry.targetNodeName ?: entry.name
        val matched = GraphData.findDestinationByKeyword(candidateName)
        if (matched != null) {
            return matched.id
        }
        return entry.nodeId
    }

    /**
     * Update or merge beacon mapping dynamically (e.g. from imported map JSON)
     */
    fun setBeaconMap(newMap: Map<Pair<Int, Int>, Int>, newUuid: String? = null) {
        if (newMap.isNotEmpty()) {
            for ((key, nodeId) in newMap) {
                val existing = entriesMap[key]
                entriesMap[key] = BeaconEntry(
                    major = key.first,
                    minor = key.second,
                    name = existing?.name ?: GraphData.getDisplayName(nodeId),
                    nodeId = nodeId,
                    targetNodeName = existing?.targetNodeName
                )
            }
            rebuildReverseIndex()
        }
        if (!newUuid.isNullOrBlank()) {
            beaconUuid = newUuid
        }
    }

    /**
     * Get node ID from beacon Major/Minor
     * @return Node ID or null if beacon not registered
     */
    fun getNodeIdForBeacon(major: Int, minor: Int): Int? {
        val entry = entriesMap[Pair(major, minor)] ?: return null
        return resolveNodeId(entry)
    }

    /**
     * Get beacon Major/Minor for a node ID
     * @return Pair(Major, Minor) or null if node doesn't have a beacon
     */
    fun getBeaconForNodeId(nodeId: Int): Pair<Int, Int>? {
        val direct = nodeToBeaconMap[nodeId]
        if (direct != null) return direct
        // Retry resolving in case GraphData changed after config load
        rebuildReverseIndex()
        return nodeToBeaconMap[nodeId]
    }

    /**
     * Get display name for a beacon (uses independent beacon name first, falls back to node name)
     */
    fun getDisplayNameForBeacon(major: Int, minor: Int): String {
        val entry = entriesMap[Pair(major, minor)]
        if (entry != null && entry.name.isNotBlank()) {
            return entry.name
        }
        val nodeId = getNodeIdForBeacon(major, minor)
        return if (nodeId != null) {
            GraphData.getDisplayName(nodeId)
        } else {
            "Unknown (M$major:m$minor)"
        }
    }

    /**
     * Check if beacon is registered
     */
    fun isBeaconRegistered(major: Int, minor: Int): Boolean {
        return entriesMap.containsKey(Pair(major, minor))
    }

    /**
     * Get all registered beacon configs
     */
    fun getAllRegisteredBeacons(): List<BeaconConfig> {
        return entriesMap.values.map { entry ->
            val resolvedId = resolveNodeId(entry) ?: -1
            BeaconConfig(
                major = entry.major,
                minor = entry.minor,
                nodeId = resolvedId,
                displayName = entry.name.ifBlank { GraphData.getDisplayName(resolvedId) }
            )
        }
    }

    /**
     * Beacon configuration data class
     */
    data class BeaconConfig(
        val major: Int,
        val minor: Int,
        val nodeId: Int,
        val displayName: String
    )
}
