package com.orion.app.navigation

/**
 * Beacon to Room Mapper
 * Maps BLE beacon Major/Minor to GraphData node IDs
 * 
 * UUID: 0112233445566778899AABBCCDDEEFF0
 */
object BeaconRoomMapper {
    
    /**
     * Mapping beacon (Major, Minor) ke node ID dalam graph
     * Sesuai dengan konfigurasi beacon YPAB
     */
    private val beaconToNodeMap = mapOf(
        Pair(1, 1)  to 29,   // Ruangan Kelas Tujuh
        Pair(1, 2)  to 30,   // Ruangan Guru
        Pair(1, 3)  to 31,   // Ruangan Kepala Sekolah
        Pair(1, 4)  to 26,   // Ruangan Gamelan
        Pair(1, 5)  to 37,   // Perpustakaan
        Pair(1, 6)  to 36,   // Ruangan Musholla
        Pair(1, 7)  to 43,   // Jalur Selatan Pendopo
        Pair(1, 8)  to 27,   // Ruangan Toilet
        Pair(1, 9)  to 35,   // Ruangan Asrama Putra
        Pair(1, 10) to 38,   // Kantin
        Pair(1, 11) to 39,   // Ruangan Kelas Dua Belas
        Pair(1, 12) to 41,   // Ruangan Musik
        Pair(1, 13) to 33,   // Parkir
        Pair(1, 14) to 34,   // Ruangan Komputer
        Pair(1, 15) to 40,   // Ruangan Kelas Delapan
        Pair(1, 16) to 23,   // Satpam
        Pair(1, 17) to 25,   // Jalur Utara Pendopo
        Pair(1, 18) to 42    // Ruangan Sekre Dua
    )
    
    /**
     * Reverse mapping: node ID ke beacon (Major, Minor)
     */
    private val nodeToBeaconMap = beaconToNodeMap.entries.associate { (k, v) -> v to k }
    
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
