package com.orion.app.navigation

/**
 * Graph Data untuk navigasi indoor Lantai 1 Gedung Rektorat UNESA
 * Data berdasarkan pengukuran langkah kaki dan bearing kompas di lapangan
 *
 * Node menggunakan ID integer (1-68)
 * Edge bersifat directional dengan bearing arah mata angin (0-360 derajat)
 */
object GraphData {

    data class Node(val id: Int, val nameSpoken: String, val isDestination: Boolean = false)
    data class Edge(val from: Int, val to: Int, val steps: Int, val bearing: Double)

    // ========== JUNCTION NODES (Titik persimpangan koridor / belokan) ==========
    private val junctionNodes = mapOf(
        5  to Node(5 , "Pertigaan Koridor Cafe"),
        6  to Node(6 , "Tikungan Co Working Space"),
        8  to Node(8 , "Pertigaan Sayap Timur"),
        9  to Node(9 , "Koridor Menuju Musholla"),
        10 to Node(10, "Pertigaan Tangga Musholla"),
        11 to Node(11, "Pertigaan Lift Utama"),
        14 to Node(14, "Koridor Antara Lift dan Tangga"),
        15 to Node(15, "Depan Musholla Pintu Satu"),
        18 to Node(18, "Pertigaan Information Center"),
        20 to Node(20, "Koridor Depan Lift Utama"),
        21 to Node(21, "Tikungan Koridor Lobby Timur"),
        22 to Node(22, "Tikungan Koridor Lobby Selatan"),
        23 to Node(23, "Pertigaan Depan Lobby"),
        25 to Node(25, "Pertigaan Akses Lobby"),
        52 to Node(52, "Pertigaan Depan Satpam"),
        55 to Node(55, "Pertigaan Tangga Perpustakaan"),
        56 to Node(56, "Pertigaan Koridor Barat"),
        57 to Node(57, "Pertigaan Lift Sayap Kiri"),
        61 to Node(61, "Pertigaan Toilet Sayap Kiri"),
        62 to Node(62, "Depan Toilet Wanita Sayap Kiri"),
        63 to Node(63, "Depan Toilet Difabel"),
        67 to Node(67, "Pertigaan Penghubung Sayap Kiri")
    )

    // ========== DESTINATION NODES (Tujuan / Ruangan) ==========
    private val destinationNodes = mapOf(
        1  to Node(1 , "Toilet Wanita Area Cafe", true),
        2  to Node(2 , "Cafe Unesa", true),
        3  to Node(3 , "Tangga Cafe", true),
        4  to Node(4 , "Toilet Pria Area Cafe", true),
        7  to Node(7 , "Co Working Space Cafe", true),
        12 to Node(12, "Tangga Musholla", true),
        13 to Node(13, "Lift Musholla", true),
        16 to Node(16, "Musholla Lantai 1 (Pintu 1)", true),
        17 to Node(17, "Musholla Lantai 1 (Pintu 2)", true),
        19 to Node(19, "Lift Koridor Utama", true),
        24 to Node(24, "Lobby", true),
        26 to Node(26, "Pintu Tengah", true),
        50 to Node(50, "Pintu Sayap Kiri", true),
        51 to Node(51, "Satpam", true),
        53 to Node(53, "Absen Perpustakaan", true),
        54 to Node(54, "Tangga Absen Perpustakaan", true),
        58 to Node(58, "Tangga Sayap Kiri", true),
        59 to Node(59, "Lift Sayap Kiri", true),
        60 to Node(60, "Working Space Sayap Kiri", true),
        64 to Node(64, "Toilet Pria Sayap Kiri", true),
        65 to Node(65, "Toilet Wanita Sayap Kiri", true),
        66 to Node(66, "Toilet Difabel Sayap Kiri", true),
        68 to Node(68, "Information Center", true),
        901 to Node(901, "Uji Coba - Titik Mulai (Hadap Lurus)", true),
        902 to Node(902, "Uji Coba - Pos 1 Belok Kanan", false),
        903 to Node(903, "Uji Coba - Pos 2 Belok Kiri", false),
        904 to Node(904, "Uji Coba - Titik Selesai", true)
    )

    // Gabungan semua default nodes (Junctions + Destinations)
    private val defaultNodes: Map<Int, Node> = junctionNodes + destinationNodes

    // Active nodes in memory (can be loaded from JSON)
    var nodes: Map<Int, Node> = defaultNodes
        private set

    // ========== EDGES dengan jumlah langkah dan bearing ==========
    // Setiap koneksi memiliki 2 edge (bolak-balik) dengan bearing masing-masing
    // Bearing dalam derajat: 0° = Utara, 90° = Timur, 180° = Selatan, 270° = Barat
    private val defaultEdges: List<Edge> = listOf(
        Edge(1 , 2 , 9 , 152.0), // Toilet Wanita Area Cafe -> Cafe Unesa
        Edge(2 , 1 , 9 , 330.0), // Cafe Unesa -> Toilet Wanita Area Cafe
        Edge(2 , 3 , 9 , 288.0), // Cafe Unesa -> Tangga Cafe
        Edge(2 , 5 , 5 , 139.0), // Cafe Unesa -> Pertigaan Koridor Cafe
        Edge(3 , 2 , 9 , 331.0), // Tangga Cafe -> Cafe Unesa
        Edge(4 , 5 , 9 , 152.0), // Toilet Pria Area Cafe -> Pertigaan Koridor Cafe
        Edge(5 , 2 , 5 , 139.0), // Pertigaan Koridor Cafe -> Cafe Unesa
        Edge(5 , 4 , 9 , 330.0), // Pertigaan Koridor Cafe -> Toilet Pria Area Cafe
        Edge(5 , 6 , 20, 240.0), // Pertigaan Koridor Cafe -> Tikungan Co Working Space
        Edge(6 , 5 , 20,  70.0), // Tikungan Co Working Space -> Pertigaan Koridor Cafe
        Edge(6 , 7 , 6 , 281.0), // Tikungan Co Working Space -> Co Working Space Cafe
        Edge(6 , 8 , 23, 195.0), // Tikungan Co Working Space -> Pertigaan Sayap Timur
        Edge(7 , 6 , 6 ,  93.0), // Co Working Space Cafe -> Tikungan Co Working Space
        Edge(8 , 6 , 23,   5.0), // Pertigaan Sayap Timur -> Tikungan Co Working Space
        Edge(8 , 9 , 20, 278.0), // Pertigaan Sayap Timur -> Koridor Menuju Musholla
        Edge(8 , 25, 15,  92.0), // Pertigaan Sayap Timur -> Pertigaan Akses Lobby
        Edge(9 , 8 , 20, 103.0), // Koridor Menuju Musholla -> Pertigaan Sayap Timur
        Edge(9 , 10, 6 , 192.0), // Koridor Menuju Musholla -> Pertigaan Tangga Musholla
        Edge(10, 9 , 6 , 358.0), // Pertigaan Tangga Musholla -> Koridor Menuju Musholla
        Edge(10, 12, 3 , 268.0), // Pertigaan Tangga Musholla -> Tangga Musholla
        Edge(10, 14, 3 , 183.0), // Pertigaan Tangga Musholla -> Koridor Antara Lift dan Tangga
        Edge(11, 15, 4 ,  13.0), // Pertigaan Lift Utama -> Depan Musholla Pintu Satu
        Edge(11, 18, 3 , 192.0), // Pertigaan Lift Utama -> Pertigaan Information Center
        Edge(11, 19, 7 , 102.0), // Pertigaan Lift Utama -> Lift Koridor Utama
        Edge(12, 10, 3 ,  94.0), // Tangga Musholla -> Pertigaan Tangga Musholla
        Edge(13, 14, 7 ,  94.0), // Lift Musholla -> Koridor Antara Lift dan Tangga
        Edge(14, 10, 3 ,   1.0), // Koridor Antara Lift dan Tangga -> Pertigaan Tangga Musholla
        Edge(14, 13, 7 , 272.0), // Koridor Antara Lift dan Tangga -> Lift Musholla
        Edge(14, 15, 11, 183.0), // Koridor Antara Lift dan Tangga -> Depan Musholla Pintu Satu
        Edge(15, 11, 4 , 181.0), // Depan Musholla Pintu Satu -> Pertigaan Lift Utama
        Edge(15, 14, 11, 345.0), // Depan Musholla Pintu Satu -> Koridor Antara Lift dan Tangga
        Edge(15, 16, 3 , 272.0), // Depan Musholla Pintu Satu -> Musholla Lantai 1 (Pintu 1)
        Edge(16, 15, 3 ,  91.0), // Musholla Lantai 1 (Pintu 1) -> Depan Musholla Pintu Satu
        Edge(17, 18, 3 ,  94.0), // Musholla Lantai 1 (Pintu 2) -> Pertigaan Information Center
        Edge(18, 11, 3 ,   4.0), // Pertigaan Information Center -> Pertigaan Lift Utama
        Edge(18, 17, 3 , 268.0), // Pertigaan Information Center -> Musholla Lantai 1 (Pintu 2)
        Edge(18, 68, 20, 175.0), // Pertigaan Information Center -> Information Center
        Edge(19, 11, 7 , 286.0), // Lift Koridor Utama -> Pertigaan Lift Utama
        Edge(19, 20, 7 , 106.0), // Lift Koridor Utama -> Koridor Depan Lift Utama
        Edge(20, 19, 7 , 277.0), // Koridor Depan Lift Utama -> Lift Koridor Utama
        Edge(20, 21, 9 ,   1.0), // Koridor Depan Lift Utama -> Tikungan Koridor Lobby Timur
        Edge(20, 22, 9 , 184.0), // Koridor Depan Lift Utama -> Tikungan Koridor Lobby Selatan
        Edge(21, 20, 9 , 186.0), // Tikungan Koridor Lobby Timur -> Koridor Depan Lift Utama
        Edge(21, 25, 5 ,  96.0), // Tikungan Koridor Lobby Timur -> Pertigaan Akses Lobby
        Edge(22, 20, 9 ,  25.0), // Tikungan Koridor Lobby Selatan -> Koridor Depan Lift Utama
        Edge(22, 23, 5 , 114.0), // Tikungan Koridor Lobby Selatan -> Pertigaan Depan Lobby
        Edge(23, 22, 5 ,  77.0), // Pertigaan Depan Lobby -> Tikungan Koridor Lobby Selatan
        Edge(23, 24, 9 ,  13.0), // Pertigaan Depan Lobby -> Lobby
        Edge(23, 67, 13, 183.0), // Pertigaan Depan Lobby -> Pertigaan Penghubung Sayap Kiri
        Edge(24, 23, 9 , 191.0), // Lobby -> Pertigaan Depan Lobby
        Edge(24, 25, 9 ,  15.0), // Lobby -> Pertigaan Akses Lobby
        Edge(24, 26, 27, 100.0), // Lobby -> Pintu Tengah
        Edge(25, 8 , 15, 272.0), // Pertigaan Akses Lobby -> Pertigaan Sayap Timur
        Edge(25, 21, 5 , 272.0), // Pertigaan Akses Lobby -> Tikungan Koridor Lobby Timur
        Edge(25, 24, 9 , 194.0), // Pertigaan Akses Lobby -> Lobby
        Edge(26, 24, 27, 288.0), // Pintu Tengah -> Lobby
        Edge(50, 51, 9 , 192.0), // Pintu Sayap Kiri -> Satpam
        Edge(51, 50, 9 ,  20.0), // Satpam -> Pintu Sayap Kiri
        Edge(51, 52, 11, 290.0), // Satpam -> Pertigaan Depan Satpam
        Edge(52, 51, 11, 162.0), // Pertigaan Depan Satpam -> Satpam
        Edge(52, 53, 5 ,  90.0), // Pertigaan Depan Satpam -> Absen Perpustakaan
        Edge(52, 55, 11,   1.0), // Pertigaan Depan Satpam -> Pertigaan Tangga Perpustakaan
        Edge(53, 52, 5 , 252.0), // Absen Perpustakaan -> Pertigaan Depan Satpam
        Edge(54, 55, 8 , 244.0), // Tangga Absen Perpustakaan -> Pertigaan Tangga Perpustakaan
        Edge(55, 52, 11,  70.0), // Pertigaan Tangga Perpustakaan -> Pertigaan Depan Satpam
        Edge(55, 54, 8 ,  72.0), // Pertigaan Tangga Perpustakaan -> Tangga Absen Perpustakaan
        Edge(55, 56, 4 , 251.0), // Pertigaan Tangga Perpustakaan -> Pertigaan Koridor Barat
        Edge(56, 55, 4 ,  72.0), // Pertigaan Koridor Barat -> Pertigaan Tangga Perpustakaan
        Edge(56, 57, 11, 233.0), // Pertigaan Koridor Barat -> Pertigaan Lift Sayap Kiri
        Edge(56, 61, 24, 340.0), // Pertigaan Koridor Barat -> Pertigaan Toilet Sayap Kiri
        Edge(57, 56, 11,  58.0), // Pertigaan Lift Sayap Kiri -> Pertigaan Koridor Barat
        Edge(57, 58, 4 , 240.0), // Pertigaan Lift Sayap Kiri -> Tangga Sayap Kiri
        Edge(57, 59, 3 , 325.0), // Pertigaan Lift Sayap Kiri -> Lift Sayap Kiri
        Edge(58, 57, 4 ,  58.0), // Tangga Sayap Kiri -> Pertigaan Lift Sayap Kiri
        Edge(59, 57, 3 , 148.0), // Lift Sayap Kiri -> Pertigaan Lift Sayap Kiri
        Edge(60, 61, 3 ,  19.0), // Working Space Sayap Kiri -> Pertigaan Toilet Sayap Kiri
        Edge(61, 56, 24, 145.0), // Pertigaan Toilet Sayap Kiri -> Pertigaan Koridor Barat
        Edge(61, 60, 3 , 194.0), // Pertigaan Toilet Sayap Kiri -> Working Space Sayap Kiri
        Edge(61, 62, 5 ,  15.0), // Pertigaan Toilet Sayap Kiri -> Depan Toilet Wanita Sayap Kiri
        Edge(61, 64, 4 , 286.0), // Pertigaan Toilet Sayap Kiri -> Toilet Pria Sayap Kiri
        Edge(62, 61, 5 , 216.0), // Depan Toilet Wanita Sayap Kiri -> Pertigaan Toilet Sayap Kiri
        Edge(62, 63, 4 ,  15.0), // Depan Toilet Wanita Sayap Kiri -> Depan Toilet Difabel
        Edge(62, 65, 4 , 283.0), // Depan Toilet Wanita Sayap Kiri -> Toilet Wanita Sayap Kiri
        Edge(63, 62, 4 , 216.0), // Depan Toilet Difabel -> Depan Toilet Wanita Sayap Kiri
        Edge(63, 66, 4 , 283.0), // Depan Toilet Difabel -> Toilet Difabel Sayap Kiri
        Edge(63, 67, 14,  15.0), // Depan Toilet Difabel -> Pertigaan Penghubung Sayap Kiri
        Edge(64, 61, 4 , 105.0), // Toilet Pria Sayap Kiri -> Pertigaan Toilet Sayap Kiri
        Edge(65, 62, 4 , 105.0), // Toilet Wanita Sayap Kiri -> Depan Toilet Wanita Sayap Kiri
        Edge(66, 63, 4 , 105.0), // Toilet Difabel Sayap Kiri -> Depan Toilet Difabel
        Edge(67, 23, 13,  19.0), // Pertigaan Penghubung Sayap Kiri -> Pertigaan Depan Lobby
        Edge(67, 63, 14, 199.0), // Pertigaan Penghubung Sayap Kiri -> Depan Toilet Difabel
        Edge(67, 68, 13, 282.0), // Pertigaan Penghubung Sayap Kiri -> Information Center
        Edge(68, 18, 20,  12.0), // Information Center -> Pertigaan Information Center
        Edge(68, 67, 13, 102.0), // Information Center -> Pertigaan Penghubung Sayap Kiri
        Edge(901, 902, 10,   0.0), // Uji Coba: Titik Mulai -> Pos 1 (Hadap Lurus 0°)
        Edge(902, 901, 10, 180.0),
        Edge(902, 903, 10,  90.0), // Uji Coba: Pos 1 -> Pos 2 (Belok Kanan 90°)
        Edge(903, 902, 10, 270.0),
        Edge(903, 904, 10,   0.0), // Uji Coba: Pos 2 -> Selesai
        Edge(904, 903, 10, 180.0)
    )

    // Active edges in memory (can be loaded from JSON)
    var edges: List<Edge> = defaultEdges
        private set

    /**
     * Helper: Dapatkan semua tujuan untuk dropdown
     */
    fun getDestinations(): List<Node> {
        return nodes.values.filter { it.isDestination }.sortedBy { it.nameSpoken }
    }

    /**
     * Helper: Dapatkan jumlah langkah antara dua node (directional)
     */
    fun getStepsBetween(fromId: Int, toId: Int): Int {
        return edges.find { it.from == fromId && it.to == toId }?.steps ?: 0
    }

    /**
     * Helper: Dapatkan bearing antara dua node (directional)
     * @return bearing dalam derajat, atau null jika edge tidak ditemukan
     */
    fun getBearingBetween(fromId: Int, toId: Int): Double? {
        return edges.find { it.from == fromId && it.to == toId }?.bearing
    }

    /**
     * Helper: Dapatkan node berdasarkan ID
     */
    fun getNode(nodeId: Int): Node? = nodes[nodeId]

    /**
     * Helper: Dapatkan semua nama ruangan untuk TTS-friendly display
     */
    fun getDestinationNames(): List<String> {
        return getDestinations().map { it.nameSpoken }
    }

    /**
     * Helper: Konversi node ID ke nama tampilan (sama dengan TTS)
     */
    fun getDisplayName(nodeId: Int): String {
        return nodes[nodeId]?.nameSpoken ?: "titik "
    }

    /**
     * Building and floor name (can be loaded from JSON)
     */
    var currentBuildingName: String = "Gedung Rektorat UNESA"
        private set

    var currentFloorName: String = "Lantai 1"
        private set

    /**
     * Update map graph data dynamically (e.g. from JSON file)
     */
    fun setMapData(
        newNodes: Map<Int, Node>,
        newEdges: List<Edge>,
        newAliases: Map<String, String>? = null,
        buildingName: String? = null,
        floorName: String? = null
    ) {
        if (newNodes.isNotEmpty()) {
            nodes = newNodes
        }
        if (newEdges.isNotEmpty()) {
            edges = newEdges
        }
        if (newAliases != null && newAliases.isNotEmpty()) {
            destinationAliases = newAliases.toMutableMap()
        }
        if (!buildingName.isNullOrBlank()) {
            currentBuildingName = buildingName
        }
        if (!floorName.isNullOrBlank()) {
            currentFloorName = floorName
        }
    }

    /**
     * Keyword aliases for voice command matching.
     * Maps English/phonetic variations and informal terms to actual destination nameSpoken.
     */
    private val defaultAliases = mapOf(
        // Cafe Unesa
        "cafe" to "Cafe Unesa",
        "kafe" to "Cafe Unesa",
        "kopi" to "Cafe Unesa",
        "warung" to "Cafe Unesa",
        "makan" to "Cafe Unesa",
        "minum" to "Cafe Unesa",

        // Toilets Area Cafe
        "toilet wanita cafe" to "Toilet Wanita Area Cafe",
        "toilet cewek cafe" to "Toilet Wanita Area Cafe",
        "wc wanita cafe" to "Toilet Wanita Area Cafe",
        "toilet pria cafe" to "Toilet Pria Area Cafe",
        "toilet cowok cafe" to "Toilet Pria Area Cafe",
        "wc pria cafe" to "Toilet Pria Area Cafe",

        // Toilets Sayap Kiri
        "toilet pria sayap kiri" to "Toilet Pria Sayap Kiri",
        "toilet pria barat" to "Toilet Pria Sayap Kiri",
        "toilet cowok sayap kiri" to "Toilet Pria Sayap Kiri",
        "toilet wanita sayap kiri" to "Toilet Wanita Sayap Kiri",
        "toilet wanita barat" to "Toilet Wanita Sayap Kiri",
        "toilet cewek sayap kiri" to "Toilet Wanita Sayap Kiri",
        "toilet difabel" to "Toilet Difabel Sayap Kiri",
        "difabel" to "Toilet Difabel Sayap Kiri",
        "disabilitas" to "Toilet Difabel Sayap Kiri",
        "toilet disabilitas" to "Toilet Difabel Sayap Kiri",

        // Tangga
        "tangga cafe" to "Tangga Cafe",
        "tangga musholla" to "Tangga Musholla",
        "tangga perpus" to "Tangga Absen Perpustakaan",
        "tangga perpustakaan" to "Tangga Absen Perpustakaan",
        "tangga sayap kiri" to "Tangga Sayap Kiri",
        "tangga barat" to "Tangga Sayap Kiri",

        // Lift
        "lift musholla" to "Lift Musholla",
        "lift koridor utama" to "Lift Koridor Utama",
        "lift utama" to "Lift Koridor Utama",
        "lift tengah" to "Lift Koridor Utama",
        "lift sayap kiri" to "Lift Sayap Kiri",
        "lift barat" to "Lift Sayap Kiri",
        "elevator" to "Lift Koridor Utama",

        // Musholla
        "musholla" to "Musholla Lantai 1 (Pintu 1)",
        "mushola" to "Musholla Lantai 1 (Pintu 1)",
        "masjid" to "Musholla Lantai 1 (Pintu 1)",
        "sholat" to "Musholla Lantai 1 (Pintu 1)",
        "surau" to "Musholla Lantai 1 (Pintu 1)",
        "musholla pintu 1" to "Musholla Lantai 1 (Pintu 1)",
        "musholla pintu 2" to "Musholla Lantai 1 (Pintu 2)",

        // Lobby & Pintu
        "lobby" to "Lobby",
        "lobi" to "Lobby",
        "ruang tunggu" to "Lobby",
        "depan" to "Lobby",
        "pintu tengah" to "Pintu Tengah",
        "pintu utama" to "Pintu Tengah",
        "pintu sayap kiri" to "Pintu Sayap Kiri",
        "pintu barat" to "Pintu Sayap Kiri",

        // Satpam & Pos
        "satpam" to "Satpam",
        "pos satpam" to "Satpam",
        "security" to "Satpam",
        "penjaga" to "Satpam",

        // Perpustakaan
        "absen perpustakaan" to "Absen Perpustakaan",
        "absen perpus" to "Absen Perpustakaan",
        "perpustakaan" to "Absen Perpustakaan",
        "perpus" to "Absen Perpustakaan",

        // Working Spaces
        "co working space" to "Co Working Space Cafe",
        "coworking space" to "Co Working Space Cafe",
        "coworking" to "Co Working Space Cafe",
        "working space" to "Working Space Sayap Kiri",
        "working space kiri" to "Working Space Sayap Kiri",

        // Information Center
        "information center" to "Information Center",
        "informasi" to "Information Center",
        "pusat informasi" to "Information Center",
        "info center" to "Information Center",
        "resepsionis" to "Information Center"
    )

    /**
     * Active aliases for voice command matching (can be loaded from JSON)
     */
    var destinationAliases: MutableMap<String, String> = defaultAliases.toMutableMap()
        private set

    /**
     * Find destination by keyword (fuzzy matching for voice commands).
     * Returns the matched Node or null.
     */
    fun findDestinationByKeyword(keyword: String): Node? {
        val normalizedKeyword = keyword.lowercase().trim()
        val destinations = getDestinations()

        // 1. Exact match on nameSpoken
        destinations.find { it.nameSpoken.lowercase() == normalizedKeyword }?.let { return it }

        // 2. Exact match on alias map
        val aliasMatch = destinationAliases[normalizedKeyword]
        if (aliasMatch != null) {
            destinations.find { it.nameSpoken.lowercase() == aliasMatch.lowercase() }?.let { return it }
        }

        // 3. Partial alias match (keyword contains an alias key or alias contains keyword)
        for ((alias, target) in destinationAliases) {
            if (normalizedKeyword.contains(alias) || alias.contains(normalizedKeyword)) {
                destinations.find { it.nameSpoken.lowercase() == target.lowercase() }?.let { return it }
            }
        }

        // 4. Contains match (destination name contains keyword or keyword contains destination name)
        destinations.find { normalizedKeyword.contains(it.nameSpoken.lowercase()) }?.let { return it }
        destinations.find { it.nameSpoken.lowercase().contains(normalizedKeyword) }?.let { return it }

        // 5. Word-level partial match (any word in keyword matches any word in destination name)
        val keywordWords = normalizedKeyword.split(" ").filter { it.length > 2 }
        destinations.find { dest ->
            val destWords = dest.nameSpoken.lowercase().split(" ")
            keywordWords.any { kw -> destWords.any { dw -> dw.contains(kw) || kw.contains(dw) } }
        }?.let { return it }

        return null
    }
}
