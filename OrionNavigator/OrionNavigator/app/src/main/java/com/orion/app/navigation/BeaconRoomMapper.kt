package com.orion.app.navigation

/**
 * Beacon to Room Mapper for UNESA Rektorat Lantai 1
 * Maps BLE beacon Major/Minor to GraphData node IDs
 * 
 * UUID: 0112233445566778899AABBCCDDEEFF0
 */
object BeaconRoomMapper {

    /**
     * Active beacon UUID (can be loaded from JSON)
     */
    var beaconUuid: String = "0112233445566778899AABBCCDDEEFF0"
        private set
    
    /**
     * Default mapping beacon (Major, Minor) ke node ID dalam graph UNESA Rektorat Lantai 1.
     */
    private val defaultBeaconMap = mapOf(
        Pair(1, 1)  to 1,   // Toilet Wanita Area Cafe
        Pair(1, 2)  to 2,   // Cafe Unesa
        Pair(1, 3)  to 3,   // Tangga Cafe
        Pair(1, 4)  to 4,   // Toilet Pria Area Cafe
        Pair(1, 7)  to 7,   // Co Working Space Cafe
        Pair(1, 12) to 12,  // Tangga Musholla
        Pair(1, 13) to 13,  // Lift Musholla
        Pair(1, 16) to 16,  // Musholla Lantai 1 (Pintu 1)
        Pair(1, 17) to 17,  // Musholla Lantai 1 (Pintu 2)
        Pair(1, 19) to 19,  // Lift Koridor Utama
        Pair(1, 24) to 24,  // Lobby
        Pair(1, 26) to 26,  // Pintu Tengah
        Pair(1, 50) to 50,  // Pintu Sayap Kiri
        Pair(1, 51) to 51,  // Satpam
        Pair(1, 53) to 53,  // Absen Perpustakaan
        Pair(1, 54) to 54,  // Tangga Absen Perpustakaan
        Pair(1, 58) to 58,  // Tangga Sayap Kiri
        Pair(1, 59) to 59,  // Lift Sayap Kiri
        Pair(1, 60) to 60,  // Working Space Sayap Kiri
        Pair(1, 64) to 64,  // Toilet Pria Sayap Kiri
        Pair(1, 65) to 65,  // Toilet Wanita Sayap Kiri
        Pair(1, 66) to 66,  // Toilet Difabel Sayap Kiri
        Pair(1, 68) to 68   // Information Center
    )

    private var beaconToNodeMap: MutableMap<Pair<Int, Int>, Int> = defaultBeaconMap.toMutableMap()
    
    /**
     * Reverse mapping: node ID ke beacon (Major, Minor)
     */
    private var nodeToBeaconMap: MutableMap<Int, Pair<Int, Int>> = 
        beaconToNodeMap.entries.associate { (k, v) -> v to k }.toMutableMap()

    /**
     * Update beacon mapping dynamically (e.g. from JSON file)
     */
    fun setBeaconMap(newMap: Map<Pair<Int, Int>, Int>, newUuid: String? = null) {
        if (newMap.isNotEmpty()) {
            beaconToNodeMap = newMap.toMutableMap()
            nodeToBeaconMap = beaconToNodeMap.entries.associate { (k, v) -> v to k }.toMutableMap()
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
        return beaconToNodeMap[Pair(major, minor)]
    }
    
    /**
     * Get beacon Major/Minor for a node ID
     * @return Pair(Major, Minor) or null if node doesn't have a beacon
     */
    fun getBeaconForNodeId(nodeId: Int): Pair<Int, Int>? {
        return nodeToBeaconMap[nodeId]
    }
    
    /**
     * Get display name for a beacon
     */
    fun getDisplayNameForBeacon(major: Int, minor: Int): String {
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
        return beaconToNodeMap.containsKey(Pair(major, minor))
    }
    
    /**
     * Get all registered beacon configs
     */
    fun getAllRegisteredBeacons(): List<BeaconConfig> {
        return beaconToNodeMap.map { (beacon, nodeId) ->
            BeaconConfig(
                major = beacon.first,
                minor = beacon.second,
                nodeId = nodeId,
                displayName = GraphData.getDisplayName(nodeId)
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
