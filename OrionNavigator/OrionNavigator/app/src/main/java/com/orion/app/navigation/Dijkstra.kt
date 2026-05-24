package com.orion.app.navigation

/**
 * Dijkstra's Algorithm untuk mencari jalur terpendek
 * Ported from YPAB project
 */
object Dijkstra {

    /**
     * Mencari jalur terpendek dari node start ke end
     * @param start Node ID awal
     * @param end Node ID tujuan
     * @return List node IDs yang membentuk jalur, termasuk start dan end
     */
    fun shortestPath(start: Int, end: Int): List<Int> {
        val nodes = GraphData.nodes.keys
        val dist = mutableMapOf<Int, Double>()
        val prev = mutableMapOf<Int, Int?>()
        val visited = mutableSetOf<Int>()

        // Initialize distances
        nodes.forEach {
            dist[it] = Double.MAX_VALUE
            prev[it] = null
        }

        dist[start] = 0.0

        while (visited.size < nodes.size) {
            // Find unvisited node with minimum distance
            val u = dist
                .filter { !visited.contains(it.key) }
                .minByOrNull { it.value }
                ?.key ?: break

            if (u == end) break

            visited.add(u)

            // Update distances for neighbors
            GraphData.edges
                .filter { it.from == u }
                .forEach { edge ->
                    val v = edge.to
                    val alt = dist[u]!! + edge.steps

                    if (alt < dist[v]!!) {
                        dist[v] = alt
                        prev[v] = u
                    }
                }
        }

        // Reconstruct path
        val path = mutableListOf<Int>()
        var curr: Int? = end

        while (curr != null) {
            path.add(0, curr)
            curr = prev[curr]
        }

        // Return empty if no path found
        return if (path.isNotEmpty() && path[0] == start) path else emptyList()
    }
    
    /**
     * Hitung total langkah dalam path
     */
    fun getTotalSteps(path: List<Int>): Int {
        if (path.size < 2) return 0
        
        var total = 0
        for (i in 0 until path.size - 1) {
            total += GraphData.getStepsBetween(path[i], path[i + 1])
        }
        return total
    }
}
