package com.orion.app.navigation

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * MapDataLoader - Handles loading, importing, and validating JSON indoor maps for Orion Navigator.
 * Supports multi-layer / multi-floor mapping:
 * - Base built-in layer: Lantai 1 (from assets indoor_navigation_map.json)
 * - Custom layers: Ground, Lantai 2, Lantai 3, etc. (stored in files/maps/)
 * - Importing a new JSON NEVER overwrites or deletes existing layers.
 */
object MapDataLoader {

    private const val TAG = "MapDataLoader"
    private const val PREFS_NAME = "orion_map_prefs"
    private const val KEY_ACTIVE_LAYER = "active_layer_id"
    private const val MAPS_DIR = "maps"
    const val BUILTIN_LAYER_ID = "builtin_lt1"
    const val BUILTIN_GROUND_LAYER_ID = "builtin_ground"
    private const val ASSET_MAP_FILE = "indoor_navigation_map.json"
    private const val ASSET_GROUND_MAP_FILE = "groundRecMap1.json"

    data class MapLayer(
        val id: String,                 // "builtin_lt1" or filename without extension
        val displayName: String,        // e.g. "Lantai 1 (Bawaan)", "Lantai 2", "Ground Floor"
        val floorName: String,          // e.g. "Lantai 1", "Lantai 2", "Ground"
        val buildingName: String,       // e.g. "Gedung Rektorat UNESA"
        val isBuiltIn: Boolean,
        val fileName: String? = null,
        val nodeCount: Int = 0,
        val destinationCount: Int = 0,
        val edgeCount: Int = 0
    )

    data class ParsedMap(
        val buildingId: String,
        val buildingName: String,
        val floorName: String,
        val beaconUuid: String,
        val nodes: Map<Int, GraphData.Node>,
        val edges: List<GraphData.Edge>,
        val beacons: Map<Pair<Int, Int>, Int>,
        val aliases: Map<String, String>
    )

    interface OnLayerChangedListener {
        fun onLayerChanged(activeLayer: MapLayer)
    }

    private val layerListeners = mutableListOf<OnLayerChangedListener>()

    fun addOnLayerChangedListener(listener: OnLayerChangedListener) {
        if (!layerListeners.contains(listener)) {
            layerListeners.add(listener)
        }
    }

    fun removeOnLayerChangedListener(listener: OnLayerChangedListener) {
        layerListeners.remove(listener)
    }

    private fun notifyLayerChanged(activeLayer: MapLayer) {
        layerListeners.forEach {
            try {
                it.onLayerChanged(activeLayer)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying layer listener", e)
            }
        }
    }

    private fun getMapsDirectory(context: Context): File {
        val dir = File(context.filesDir, MAPS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        // Migrate legacy single custom_indoor_map.json if present
        migrateLegacyFile(context, dir)
        return dir
    }

    private fun migrateLegacyFile(context: Context, mapsDir: File) {
        try {
            val legacyFile = File(context.filesDir, "custom_indoor_map.json")
            if (legacyFile.exists() && legacyFile.length() > 0) {
                val targetFile = File(mapsDir, "layer_migrated_${System.currentTimeMillis()}.json")
                legacyFile.copyTo(targetFile, overwrite = true)
                legacyFile.delete()
                Log.i(TAG, "Migrated legacy custom map to ${targetFile.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate legacy custom map", e)
        }
    }

    /**
     * Load map on startup or resume. Loads the currently active layer.
     */
    fun loadMap(context: Context): Boolean {
        val activeId = getActiveLayerId(context)
        if (activeId == BUILTIN_GROUND_LAYER_ID) {
            if (loadBuiltInGroundMap(context)) return true
        } else if (activeId != BUILTIN_LAYER_ID) {
            val mapsDir = getMapsDirectory(context)
            val file = File(mapsDir, "$activeId.json")
            if (file.exists() && file.length() > 0) {
                try {
                    val json = file.readText(Charsets.UTF_8)
                    val parsed = parseJson(json, file.name)
                    if (parsed != null && parsed.nodes.isNotEmpty() && parsed.edges.isNotEmpty()) {
                        applyParsedMap(parsed, parsed.floorName)
                        Log.i(TAG, "Successfully loaded active layer: ${parsed.floorName} (${file.name})")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load active layer $activeId, falling back to built-in Lantai 1", e)
                }
            }
        }

        // Fallback to built-in Lantai 1
        return loadBuiltInMap(context)
    }

    private fun loadBuiltInMap(context: Context): Boolean {
        return try {
            context.assets.open(ASSET_MAP_FILE).use { inputStream ->
                val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseJson(jsonString, "indoor_navigation_map.json")
                if (parsed != null) {
                    applyParsedMap(parsed, "Lantai 1 (Bawaan)")
                    setActiveLayerId(context, BUILTIN_LAYER_ID)
                    Log.i(TAG, "Successfully loaded built-in map from assets/$ASSET_MAP_FILE")
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load built-in map from assets", e)
            false
        }
    }

    private fun loadBuiltInGroundMap(context: Context): Boolean {
        return try {
            context.assets.open(ASSET_GROUND_MAP_FILE).use { inputStream ->
                val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseJson(jsonString, "groundRecMap1.json")
                if (parsed != null) {
                    applyParsedMap(parsed, "Lantai Ground (Hasil Pemetaan)")
                    setActiveLayerId(context, BUILTIN_GROUND_LAYER_ID)
                    Log.i(TAG, "Successfully loaded built-in ground map from assets/$ASSET_GROUND_MAP_FILE")
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load built-in ground map from assets", e)
            false
        }
    }

    fun getActiveLayerId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_LAYER, BUILTIN_LAYER_ID) ?: BUILTIN_LAYER_ID
    }

    private fun setActiveLayerId(context: Context, layerId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_LAYER, layerId).apply()
    }

    /**
     * Get all available map layers (Built-in Lantai 1 + all imported layers).
     */
    fun getAvailableLayers(context: Context): List<MapLayer> {
        val layers = mutableListOf<MapLayer>()

        // 1. Built-in Lantai 1 layer
        var builtinNodes = 68
        var builtinDests = 23
        var builtinEdges = 173
        try {
            context.assets.open(ASSET_MAP_FILE).use { stream ->
                val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseJson(json)
                if (parsed != null) {
                    builtinNodes = parsed.nodes.size
                    builtinDests = parsed.nodes.values.count { it.isDestination }
                    builtinEdges = parsed.edges.size
                }
            }
        } catch (e: Exception) {
            // Use defaults
        }

        layers.add(
            MapLayer(
                id = BUILTIN_LAYER_ID,
                displayName = "Lantai 1 (Bawaan)",
                floorName = "Lantai 1",
                buildingName = "Gedung Rektorat UNESA",
                isBuiltIn = true,
                nodeCount = builtinNodes,
                destinationCount = builtinDests,
                edgeCount = builtinEdges
            )
        )

        // 2. Built-in Ground Floor (Helios Survey Map)
        try {
            context.assets.open(ASSET_GROUND_MAP_FILE).use { stream ->
                val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseJson(json, "groundRecMap1.json")
                if (parsed != null) {
                    val destCount = parsed.nodes.values.count { it.isDestination }
                    layers.add(
                        MapLayer(
                            id = BUILTIN_GROUND_LAYER_ID,
                            displayName = "Lantai Ground (Hasil Pemetaan)",
                            floorName = "Lantai Ground",
                            buildingName = parsed.buildingName,
                            isBuiltIn = true,
                            nodeCount = parsed.nodes.size,
                            destinationCount = destCount,
                            edgeCount = parsed.edges.size
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ground asset not found or failed to parse
        }

        // 3. Custom imported layers
        val mapsDir = getMapsDirectory(context)
        val jsonFiles = mapsDir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        for (file in jsonFiles) {
            try {
                val json = file.readText(Charsets.UTF_8)
                val parsed = parseJson(json) ?: continue
                val id = file.nameWithoutExtension
                val destCount = parsed.nodes.values.count { it.isDestination }
                layers.add(
                    MapLayer(
                        id = id,
                        displayName = "${parsed.floorName} (Kustom)",
                        floorName = parsed.floorName,
                        buildingName = parsed.buildingName,
                        isBuiltIn = false,
                        fileName = file.name,
                        nodeCount = parsed.nodes.size,
                        destinationCount = destCount,
                        edgeCount = parsed.edges.size
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse layer file ${file.name}", e)
            }
        }

        return layers
    }

    /**
     * Get currently active layer metadata.
     */
    fun getActiveLayer(context: Context): MapLayer {
        val activeId = getActiveLayerId(context)
        val layers = getAvailableLayers(context)
        return layers.find { it.id == activeId } ?: layers.firstOrNull() ?: MapLayer(
            id = BUILTIN_LAYER_ID,
            displayName = "Lantai 1 (Bawaan)",
            floorName = "Lantai 1",
            buildingName = "Gedung Rektorat UNESA",
            isBuiltIn = true
        )
    }

    /**
     * Switch active layer to the given layer ID.
     */
    fun setActiveLayer(context: Context, layerId: String): Boolean {
        if (layerId == BUILTIN_LAYER_ID) {
            if (loadBuiltInMap(context)) {
                setActiveLayerId(context, BUILTIN_LAYER_ID)
                notifyLayerChanged(getActiveLayer(context))
                return true
            }
            return false
        }
        if (layerId == BUILTIN_GROUND_LAYER_ID) {
            if (loadBuiltInGroundMap(context)) {
                setActiveLayerId(context, BUILTIN_GROUND_LAYER_ID)
                notifyLayerChanged(getActiveLayer(context))
                return true
            }
            return false
        }

        val mapsDir = getMapsDirectory(context)
        val file = File(mapsDir, "$layerId.json")
        if (!file.exists()) {
            Log.e(TAG, "Layer file not found: ${file.absolutePath}")
            return false
        }

        return try {
            val json = file.readText(Charsets.UTF_8)
            val parsed = parseJson(json)
            if (parsed != null && parsed.nodes.isNotEmpty() && parsed.edges.isNotEmpty()) {
                applyParsedMap(parsed, parsed.floorName)
                setActiveLayerId(context, layerId)
                notifyLayerChanged(getActiveLayer(context))
                Log.i(TAG, "Switched active layer to: ${parsed.floorName}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch layer to $layerId", e)
            false
        }
    }

    /**
     * Import a new JSON map as a new layer.
     * PREVIOUS LAYERS ARE NEVER DELETED OR OVERWRITTEN.
     */
    fun importMapFromJson(context: Context, jsonString: String, customFloorName: String? = null): Result<String> {
        return try {
            val parsed = parseJson(jsonString, customFloorName)
                ?: return Result.failure(IllegalArgumentException("Format JSON tidak valid atau data kosong"))

            if (parsed.nodes.isEmpty()) {
                return Result.failure(IllegalArgumentException("File JSON tidak memiliki daftar 'nodes' yang valid"))
            }

            if (parsed.edges.isEmpty()) {
                return Result.failure(IllegalArgumentException("File JSON tidak memiliki daftar 'edges' yang valid"))
            }

            val floorName = customFloorName?.trim()?.takeIf { it.isNotBlank() }
                ?: parsed.floorName.takeIf { it.isNotBlank() }
                ?: "Lantai Baru"

            val mapsDir = getMapsDirectory(context)
            val sanitized = floorName.lowercase().replace(Regex("[^a-z0-9]"), "_")
            val newFile = File(mapsDir, "layer_${sanitized}_${System.currentTimeMillis()}.json")
            newFile.writeText(jsonString, Charsets.UTF_8)

            // Switch to the newly imported layer
            val newLayerId = newFile.nameWithoutExtension
            setActiveLayerId(context, newLayerId)
            applyParsedMap(parsed.copy(floorName = floorName), floorName)

            val destCount = parsed.nodes.values.count { it.isDestination }
            val totalLayers = getAvailableLayers(context).size
            val message = "Layer '$floorName' (${parsed.buildingName}) berhasil ditambahkan!\nTotal ${parsed.nodes.size} titik ($destCount ruangan), ${parsed.edges.size} jalur.\n(Tersedia $totalLayers layer denah tersimpan)"
            
            Log.i(TAG, message)
            notifyLayerChanged(getActiveLayer(context))
            Result.success(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import map as layer", e)
            Result.failure(e)
        }
    }

    /**
     * Import from stream (e.g. FilePicker Uri)
     */
    fun importMapFromStream(context: Context, inputStream: InputStream, customFloorName: String? = null): Result<String> {
        return try {
            val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            importMapFromJson(context, jsonString, customFloorName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a specific custom layer. Built-in layer cannot be deleted.
     */
    fun deleteLayer(context: Context, layerId: String): Boolean {
        if (layerId == BUILTIN_LAYER_ID || layerId == BUILTIN_GROUND_LAYER_ID) {
            Log.w(TAG, "Cannot delete built-in layer")
            return false
        }

        val mapsDir = getMapsDirectory(context)
        val file = File(mapsDir, "$layerId.json")
        val deleted = if (file.exists()) file.delete() else false

        if (deleted) {
            val currentActive = getActiveLayerId(context)
            if (currentActive == layerId) {
                // Revert to built-in Lantai 1
                setActiveLayer(context, BUILTIN_LAYER_ID)
            } else {
                notifyLayerChanged(getActiveLayer(context))
            }
        }
        return deleted
    }

    /**
     * Reset active layer back to built-in Lantai 1.
     */
    fun resetToDefault(context: Context): Boolean {
        return setActiveLayer(context, BUILTIN_LAYER_ID)
    }

    /**
     * Check if a custom layer is currently active.
     */
    fun isCustomMapActive(context: Context): Boolean {
        val active = getActiveLayerId(context)
        return active != BUILTIN_LAYER_ID && active != BUILTIN_GROUND_LAYER_ID
    }

    // =========================================================================
    // Parsing Logic
    // =========================================================================

    private fun applyParsedMap(parsed: ParsedMap, sourceName: String) {
        GraphData.setMapData(
            newNodes = parsed.nodes,
            newEdges = parsed.edges,
            newAliases = parsed.aliases,
            buildingName = parsed.buildingName,
            floorName = parsed.floorName
        )

        if (parsed.beacons.isNotEmpty()) {
            BeaconRoomMapper.setBeaconMap(
                newMap = parsed.beacons,
                newUuid = parsed.beaconUuid
            )
        }

        Log.i(TAG, "Applied layer '$sourceName': ${parsed.nodes.size} nodes, ${parsed.edges.size} edges, ${parsed.beacons.size} beacons")
    }

    private fun parseJson(jsonString: String, hintFileName: String? = null): ParsedMap? {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) return null

        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON root object", e)
            return null
        }

        val buildingId = root.optString("buildingId", "map_${System.currentTimeMillis()}")
        val rawBuildingName = root.optString("buildingName", root.optString("name", "Gedung Rektorat UNESA"))
        val beaconUuid = root.optString("beaconUuid", root.optString("uuid", "0112233445566778899AABBCCDDEEFF0"))

        // Extract Floor / Layer name intelligently
        val floorName = extractFloorName(root, rawBuildingName, hintFileName)
        val buildingName = sanitizeBuildingName(rawBuildingName)

        // 1. Parse Nodes
        val nodesMap = mutableMapOf<Int, GraphData.Node>()
        val nodesArray = root.optJSONArray("nodes")
        if (nodesArray != null) {
            for (i in 0 until nodesArray.length()) {
                val nodeObj = nodesArray.optJSONObject(i) ?: continue
                val id = nodeObj.optInt("id", nodeObj.optInt("nodeId", -1))
                if (id <= 0) continue

                val name = nodeObj.optString("name", nodeObj.optString("nameSpoken", nodeObj.optString("displayName", "Titik $id")))
                
                val isDestination = if (nodeObj.has("isDestination")) {
                    nodeObj.optBoolean("isDestination", false)
                } else {
                    val lower = name.lowercase()
                    !lower.contains("pertigaan") && !lower.contains("tikungan") && !lower.contains("koridor")
                }

                nodesMap[id] = GraphData.Node(id = id, nameSpoken = name, isDestination = isDestination)
            }
        }

        // Smart destination fallback: If NO nodes are marked as destination
        // (common in raw survey exports like Helios where isDestination was false by default):
        // Automatically mark all non-junction room nodes as destinations!
        if (nodesMap.isNotEmpty() && nodesMap.values.none { it.isDestination }) {
            for ((id, node) in nodesMap) {
                val lower = node.nameSpoken.lowercase()
                val isJunction = lower.contains("pertigaan") || lower.contains("tikungan") || 
                                 lower.contains("koridor") || lower.contains("junction") || lower.contains("cross")
                nodesMap[id] = node.copy(isDestination = !isJunction)
            }
            if (nodesMap.values.none { it.isDestination }) {
                for ((id, node) in nodesMap) {
                    nodesMap[id] = node.copy(isDestination = true)
                }
            }
            Log.i(TAG, "Applied smart destination fallback: ${nodesMap.values.count { it.isDestination }} rooms marked as destination")
        }

        // 2. Parse Edges
        val edgesList = mutableListOf<GraphData.Edge>()
        val edgesArray = root.optJSONArray("edges")
        if (edgesArray != null) {
            for (i in 0 until edgesArray.length()) {
                val edgeObj = edgesArray.optJSONObject(i) ?: continue
                val from = edgeObj.optInt("from", edgeObj.optInt("fromId", edgeObj.optInt("start", -1)))
                val to = edgeObj.optInt("to", edgeObj.optInt("toId", edgeObj.optInt("end", -1)))
                val steps = edgeObj.optInt("steps", edgeObj.optInt("stepCount", edgeObj.optInt("distance", 0)))
                val bearing = edgeObj.optDouble(
                    "departureDegree",
                    edgeObj.optDouble("departure_degree",
                    edgeObj.optDouble("bearing",
                    edgeObj.optDouble("degree",
                    edgeObj.optDouble("angle",
                    edgeObj.optDouble("degrees", 0.0)))))
                )

                if (from > 0 && to > 0 && steps > 0) {
                    var normBearing = bearing % 360.0
                    if (normBearing < 0) normBearing += 360.0

                    edgesList.add(GraphData.Edge(from = from, to = to, steps = steps, bearing = normBearing))
                }
            }
        }

        // Auto-generate missing reverse edges if friend mapped one-way
        val edgeSet = edgesList.map { Pair(it.from, it.to) }.toSet()
        val complementaryEdges = mutableListOf<GraphData.Edge>()
        for (edge in edgesList) {
            val reversePair = Pair(edge.to, edge.from)
            if (!edgeSet.contains(reversePair)) {
                val reverseBearing = (edge.bearing + 180.0) % 360.0
                complementaryEdges.add(GraphData.Edge(from = edge.to, to = edge.from, steps = edge.steps, bearing = reverseBearing))
            }
        }
        edgesList.addAll(complementaryEdges)

        // 3. Parse Beacons (optional)
        val beaconsMap = mutableMapOf<Pair<Int, Int>, Int>()
        val beaconsArray = root.optJSONArray("beacons")
        if (beaconsArray != null) {
            for (i in 0 until beaconsArray.length()) {
                val bObj = beaconsArray.optJSONObject(i) ?: continue
                val major = bObj.optInt("major", 1)
                val minor = bObj.optInt("minor", -1)
                val nodeId = bObj.optInt("nodeId", bObj.optInt("id", -1))

                if (minor >= 0 && nodeId > 0) {
                    beaconsMap[Pair(major, minor)] = nodeId
                }
            }
        }

        // 4. Parse Aliases (optional)
        val aliasesMap = mutableMapOf<String, String>()
        val aliasesObj = root.optJSONObject("aliases")
        if (aliasesObj != null) {
            val keys = aliasesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = aliasesObj.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) {
                    aliasesMap[k.lowercase().trim()] = v
                }
            }
        }

        return ParsedMap(
            buildingId = buildingId,
            buildingName = buildingName,
            floorName = floorName,
            beaconUuid = beaconUuid,
            nodes = nodesMap,
            edges = edgesList,
            beacons = beaconsMap,
            aliases = aliasesMap
        )
    }

    private fun extractFloorName(root: JSONObject, fallbackName: String, hintName: String? = null): String {
        val directFields = listOf("floor", "floorName", "layer", "layerName", "level")
        for (field in directFields) {
            val value = root.optString(field, "").trim()
            if (value.isNotEmpty()) return value
        }

        val floorRegex = Regex("""(Lantai\s*\d+|Lt\.\s*\d+|Ground(\s*Floor)?|Basement(\s*\d+)?)""", RegexOption.IGNORE_CASE)

        // 1. Check hint filename if provided
        if (!hintName.isNullOrBlank()) {
            val matchHint = floorRegex.find(hintName)
            if (matchHint != null) {
                val matched = matchHint.value.trim()
                return if (matched.equals("ground", ignoreCase = true)) "Lantai Ground" else matched
            }
            if (hintName.contains("ground", ignoreCase = true)) return "Lantai Ground"
        }

        // 2. Check regex from fallback name (e.g. "Gedung Rektorat UNESA Lantai 2", "Ground Floor")
        val match = floorRegex.find(fallbackName)
        if (match != null) {
            val matched = match.value.trim()
            return if (matched.equals("ground", ignoreCase = true)) "Lantai Ground" else matched
        }

        if (fallbackName.contains("ground", ignoreCase = true)) return "Lantai Ground"
        return if (fallbackName.contains("rektorat", ignoreCase = true)) "Lantai 1" else "Lantai Kustom"
    }

    private fun sanitizeBuildingName(raw: String): String {
        return raw.replace(Regex("""(?i)\s*(Lantai\s*\d+|Lt\.\s*\d+|Ground(\s*Floor)?|Basement(\s*\d+)?)\s*"""), " ").trim().ifBlank {
            "Gedung Navigasi"
        }
    }
}
