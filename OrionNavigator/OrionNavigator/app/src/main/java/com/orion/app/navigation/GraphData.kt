package com.orion.app.navigation

/**
 * Graph Data untuk navigasi indoor YPAB
 * Data berdasarkan pengukuran langkah kaki dan bearing kompas di lapangan
 *
 * Node menggunakan ID integer (1-44)
 * Edge bersifat directional dengan bearing arah mata angin
 */
object GraphData {

    data class Node(val id: Int, val nameSpoken: String, val isDestination: Boolean = false)
    data class Edge(val from: Int, val to: Int, val steps: Int, val bearing: Double)

    // ========== JUNCTION NODES (Titik persimpangan koridor) ==========
    private val junctionNodes = mapOf(
        1  to Node(1,  "Utara Satpam"),
        2  to Node(2,  "Barat Pendopo Selatan"),
        3  to Node(3,  "Timur Gamelan Bawah"),
        4  to Node(4,  "Pertigaan Kelas 7 Barat"),
        5  to Node(5,  "Pertigaan Kelas 7 Selatan"),
        6  to Node(6,  "Barat Musholla"),
        7  to Node(7,  "Tengah Atas Taman"),
        8  to Node(8,  "Tengah Atas Taman Timur"),
        9  to Node(9,  "Depan Taman Barat"),
        10 to Node(10, "Depan Taman Timur"),
        11 to Node(11, "Pertigaan Asrama Putri"),
        12 to Node(12, "Depan Ruang Guru"),
        13 to Node(13, "Koridor Menuju Kantin"),
        14 to Node(14, "Koridor Kelas 12"),
        15 to Node(15, "Depan Ruang Musik Selatan"),
        16 to Node(16, "Ujung Kelas 8"),
        17 to Node(17, "Pertigaan Kepsek Bawah"),
        18 to Node(18, "Sekre Dua Atas"),
        19 to Node(19, "Sekre Dua Tengah"),
        20 to Node(20, "Timur Musholla"),
        21 to Node(21, "Depan Gamelan Atas"),
        22 to Node(22, "Tikungan Gamelan Tengah"),
        24 to Node(24, "Barat Pendopo Timur"),
        28 to Node(28, "Depan Perpustakaan"),
        32 to Node(32, "Depan Parkir"),
        44 to Node(44, "Pertigaan Kepsek Atas")
    )

    // ========== DESTINATION NODES (Tujuan / Ruangan) ==========
    private val destinationNodes = mapOf(
        23 to Node(23, "Satpam", true),
        25 to Node(25, "Jalur Utara Pendopo", true),
        26 to Node(26, "Ruangan Gamelan", true),
        27 to Node(27, "Ruangan Toilet", true),
        29 to Node(29, "Ruangan Kelas Tujuh", true),
        30 to Node(30, "Ruangan Guru", true),
        31 to Node(31, "Ruangan Kepala Sekolah", true),
        33 to Node(33, "Parkir", true),
        37 to Node(37, "Perpustakaan", true),
        34 to Node(34, "Ruangan Komputer", true),
        35 to Node(35, "Ruangan Asrama Putra", true),
        36 to Node(36, "Ruangan Musholla", true),
        38 to Node(38, "Kantin", true),
        39 to Node(39, "Ruangan Kelas Dua Belas", true),
        40 to Node(40, "Ruangan Kelas Delapan", true),
        41 to Node(41, "Ruangan Musik", true),
        42 to Node(42, "Ruangan Sekre Dua", true),
        43 to Node(43, "Jalur Selatan Pendopo", true)
    )


    // Gabungan semua nodes
    val nodes: Map<Int, Node> = junctionNodes + destinationNodes

    // ========== EDGES dengan jumlah langkah dan bearing ==========
    // Setiap koneksi memiliki 2 edge (maju dan mundur) dengan bearing masing-masing
    // Bearing dalam derajat (0° = Utara, 90° = Timur, 180° = Selatan, 270° = Barat)
    val edges: List<Edge> = listOf(
        // === Area Satpam & Pendopo (Selatan) ===
        Edge(1, 2, 41, 152.0),
        Edge(2, 1, 41, 8.0),
        Edge(1, 19, 15, 322.0),
        Edge(19, 1, 15, 90.0),
        Edge(1, 23, 18, 79.0),
        Edge(23, 1, 18, 260.0),

        // === Pendopo area ===
        Edge(2, 24, 17, 277.0),
        Edge(24, 2, 17, 94.0),
        Edge(24, 25, 2, 8.0),
        Edge(25, 24, 2, 186.0),
        Edge(24, 3, 16, 186.0),
        Edge(3, 24, 16, 11.0),

        // === Gamelan area ===
        Edge(3, 21, 4, 275.0),
        Edge(21, 3, 4, 94.0),
        Edge(21, 26, 2, 188.0),
        Edge(26, 21, 2, 8.0),
        Edge(21, 22, 19, 275.0),
        Edge(22, 21, 19, 94.0),
        Edge(22, 27, 2, 184.0),
        Edge(27, 22, 2, 185.0),

        // === Kelas 7 & Perpustakaan area ===
        Edge(22, 4, 15, 272.0),
        Edge(4, 22, 15, 94.0),
        Edge(4, 28, 3, 185.0),
        Edge(28, 4, 3, 11.0),
        Edge(28, 29, 3, 275.0),
        Edge(29, 28, 3, 92.0),
        Edge(4, 5, 9, 11.0),
        Edge(5, 4, 9, 187.0),

        // === Koridor Guru & Kepsek ===
        Edge(5, 12, 13, 9.0),
        Edge(12, 5, 13, 189.0),
        Edge(12, 30, 2, 102.0),
        Edge(30, 12, 2, 282.0),
        Edge(12, 44, 14, 9.0),
        Edge(44, 12, 14, 189.0),
        Edge(44, 31, 2, 102.0),
        Edge(31, 44, 2, 282.0),
        Edge(44, 17, 13, 9.0),
        Edge(17, 44, 13, 185.0),

        // === Sekretariat 2 area ===
        Edge(17, 18, 19, 86.0),
        Edge(18, 17, 19, 271.0),
        Edge(18, 42, 7, 188.0),
        Edge(42, 18, 7, 8.0),
        Edge(18, 19, 7, 96.0),
        Edge(19, 18, 7, 271.0),
        Edge(19, 43, 7, 189.0),
        Edge(43, 19, 7, 8.0),

        // === Asrama Putri & Taman Timur ===
        Edge(17, 11, 16, 273.0),
        Edge(11, 17, 16, 87.0),
        Edge(11, 10, 8, 175.0),
        Edge(10, 11, 8, 355.0),

        // === Parkir area ===
        Edge(10, 32, 8, 191.0),
        Edge(32, 10, 8, 7.0),
        Edge(32, 33, 6, 272.0),
        Edge(33, 32, 6, 102.0),

        // === Taman Barat & Komputer ===
        Edge(32, 9, 10, 191.0),
        Edge(9, 32, 10, 10.0),
        Edge(9, 34, 12, 272.0),
        Edge(34, 9, 12, 102.0),
        Edge(9, 8, 10, 187.0),
        Edge(8, 9, 10, 6.0),

        // === Tengah Taman ===
        Edge(8, 7, 8, 189.0),
        Edge(7, 8, 8, 6.0),
        Edge(8, 5, 14, 97.0),
        Edge(5, 8, 14, 270.0),

        // === Asrama Putra & Musholla ===
        Edge(7, 35, 12, 272.0),
        Edge(35, 7, 12, 102.0),
        Edge(7, 20, 6, 189.0),
        Edge(20, 7, 6, 6.0),
        Edge(20, 37, 4, 97.0),
        Edge(37, 20, 4, 272.0),
        Edge(20, 6, 12, 189.0),
        Edge(6, 20, 12, 6.0),
        Edge(6, 36, 6, 272.0),
        Edge(36, 6, 6, 102.0),

        // === Kantin & Kelas 12 ===
        Edge(11, 13, 21, 272.0),
        Edge(13, 11, 21, 95.0),
        Edge(13, 38, 20, 9.0),
        Edge(38, 13, 20, 186.0),
        Edge(13, 14, 3, 273.0),
        Edge(14, 13, 3, 95.0),
        Edge(14, 39, 8, 8.0),
        Edge(39, 14, 8, 186.0),

        // === Musik & Kelas 8 ===
        Edge(14, 15, 6, 273.0),
        Edge(15, 14, 6, 95.0),
        Edge(15, 41, 10, 186.0),
        Edge(41, 15, 10, 9.0),
        Edge(15, 16, 11, 273.0),
        Edge(16, 15, 11, 97.0),
        Edge(16, 40, 8, 9.0),
        Edge(40, 16, 8, 186.0)
    )

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
        return nodes[nodeId]?.nameSpoken ?: "titik $nodeId"
    }
    
    /**
     * Keyword aliases for voice command matching.
     * Maps English/phonetic variations (what Vosk might hear) to actual destination nameSpoken.
     * Also includes shortened Indonesian names.
     */
    private val destinationAliases = mapOf(
        // English translations / phonetic variations
        "canteen" to "kantin",
        "cafeteria" to "kantin",
        "contin" to "kantin",
        "content" to "kantin",
        "cantin" to "kantin",
        "mosque" to "ruangan musholla",
        "moschella" to "ruangan musholla",
        "musholla" to "ruangan musholla",
        "mushola" to "ruangan musholla",
        "prayer" to "ruangan musholla",
        "library" to "perpustakaan",
        "perpus" to "perpustakaan",
        "toilet" to "ruangan toilet",
        "bathroom" to "ruangan toilet",
        "restroom" to "ruangan toilet",
        "parking" to "parkir",
        "park" to "parkir",
        "computer" to "ruangan komputer",
        "komputer" to "ruangan komputer",
        "music" to "ruangan musik",
        "musik" to "ruangan musik",
        "teacher" to "ruangan guru",
        "guru" to "ruangan guru",
        "principal" to "ruangan kepala sekolah",
        "kepsek" to "ruangan kepala sekolah",
        "kepala sekolah" to "ruangan kepala sekolah",
        "security" to "satpam",
        "guard" to "satpam",
        "gamelan" to "ruangan gamelan",
        "class seven" to "ruangan kelas tujuh",
        "class 7" to "ruangan kelas tujuh",
        "kelas 7" to "ruangan kelas tujuh",
        "kelas tujuh" to "ruangan kelas tujuh",
        "class eight" to "ruangan kelas delapan",
        "class 8" to "ruangan kelas delapan",
        "kelas 8" to "ruangan kelas delapan",
        "kelas delapan" to "ruangan kelas delapan",
        "class twelve" to "ruangan kelas dua belas",
        "class 12" to "ruangan kelas dua belas",
        "kelas 12" to "ruangan kelas dua belas",
        "kelas dua belas" to "ruangan kelas dua belas",
        "dormitory" to "ruangan asrama putra",
        "asrama" to "ruangan asrama putra",
        "asrama putra" to "ruangan asrama putra",
        "secretariat" to "ruangan sekre dua",
        "sekre" to "ruangan sekre dua",
        "pendopo" to "jalur utara pendopo"
    )
    
    /**
     * Find destination by keyword (fuzzy matching for voice commands).
     * Returns the matched Node or null.
     * 
     * Matching priority:
     * 1. Exact match on nameSpoken
     * 2. Keyword alias map
     * 3. Contains match (keyword in name or name in keyword)
     * 4. Any word-level partial match
     */
    fun findDestinationByKeyword(keyword: String): Node? {
        val normalizedKeyword = keyword.lowercase().trim()
        val destinations = getDestinations()
        
        // 1. Exact match
        destinations.find { it.nameSpoken.lowercase() == normalizedKeyword }?.let { return it }
        
        // 2. Alias map
        val aliasMatch = destinationAliases[normalizedKeyword]
        if (aliasMatch != null) {
            destinations.find { it.nameSpoken.lowercase() == aliasMatch.lowercase() }?.let { return it }
        }
        
        // 2b. Partial alias match (keyword contains an alias key)
        for ((alias, target) in destinationAliases) {
            if (normalizedKeyword.contains(alias)) {
                destinations.find { it.nameSpoken.lowercase() == target.lowercase() }?.let { return it }
            }
        }
        
        // 3. Contains match (destination name contains keyword or keyword contains destination name)
        destinations.find { normalizedKeyword.contains(it.nameSpoken.lowercase()) }?.let { return it }
        destinations.find { it.nameSpoken.lowercase().contains(normalizedKeyword) }?.let { return it }
        
        // 4. Word-level partial match (any word in keyword matches any word in destination name)
        val keywordWords = normalizedKeyword.split(" ").filter { it.length > 2 }
        destinations.find { dest ->
            val destWords = dest.nameSpoken.lowercase().split(" ")
            keywordWords.any { kw -> destWords.any { dw -> dw.contains(kw) || kw.contains(dw) } }
        }?.let { return it }
        
        return null
    }
}

