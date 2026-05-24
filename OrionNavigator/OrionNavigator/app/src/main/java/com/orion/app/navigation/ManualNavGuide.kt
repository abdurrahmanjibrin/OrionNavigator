package com.orion.app.navigation

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Manual Navigation Guide
 * Menghitung arah (bearing) dan jumlah langkah untuk navigasi manual node-to-node
 *
 * Bearing diambil langsung dari data survey (edge data), bukan dihitung dari koordinat.
 */
object ManualNavGuide {
    
    /**
     * Hitung instruksi belok relatif terhadap heading user saat ini
     * @param currentHeading heading user dari compass (0° = Utara)
     * @param targetBearing arah target dari edge data
     * @return Instruksi belok dengan derajat
     */
    fun calculateTurnInstruction(currentHeading: Float, targetBearing: Double): TurnInstruction {
        // Hitung selisih sudut
        var diff = targetBearing - currentHeading
        
        // Normalize ke -180 sampai 180
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        
        val absDiff = abs(diff).roundToInt()
        
        return when {
            absDiff < 15 -> TurnInstruction(TurnDirection.STRAIGHT, absDiff)
            diff > 0 -> TurnInstruction(TurnDirection.RIGHT, absDiff)
            else -> TurnInstruction(TurnDirection.LEFT, absDiff)
        }
    }
    
    /**
     * Dapatkan jumlah langkah antara dua node
     */
    fun getStepsBetween(fromId: Int, toId: Int): Int {
        return GraphData.getStepsBetween(fromId, toId)
    }
    
    /**
     * Generate instruksi TTS lengkap untuk bergerak dari node saat ini ke node berikutnya
     */
    fun generateNavInstruction(
        fromNode: GraphData.Node,
        toNode: GraphData.Node,
        currentHeading: Float
    ): NavigationInstruction {
        // Use survey-measured bearing from edge data
        val targetBearing = GraphData.getBearingBetween(fromNode.id, toNode.id)
            ?: 0.0  // Fallback — should not happen with correct data
        val turn = calculateTurnInstruction(currentHeading, targetBearing)
        val steps = getStepsBetween(fromNode.id, toNode.id)
        val destinationName = GraphData.getDisplayName(toNode.id)
        
        return NavigationInstruction(
            turnDirection = turn.direction,
            turnDegrees = turn.degrees,
            targetBearing = targetBearing,
            stepCount = steps,
            destinationName = destinationName
        )
    }
    
    /**
     * Convert instruction ke teks TTS bahasa Indonesia
     * Selalu menyebutkan derajat (termasuk untuk lurus)
     */
    fun instructionToSpeech(instruction: NavigationInstruction): String {
        val turnText = when (instruction.turnDirection) {
            TurnDirection.STRAIGHT -> "Jalan lurus"
            TurnDirection.LEFT -> "Hadap kiri ${instruction.turnDegrees} derajat"
            TurnDirection.RIGHT -> "Hadap kanan ${instruction.turnDegrees} derajat"
        }
        
        return "$turnText, jalan ${instruction.stepCount} langkah menuju ${instruction.destinationName}"
    }
    
    /**
     * Generate speech text untuk alignment guidance
     * @param diff selisih derajat
     * @param tolerance rentang aman derajat (default 15)
     */
    fun getAlignmentGuidance(diff: Float, tolerance: Float = 15f): String {
        val absDiff = abs(diff).roundToInt()
        return when {
            absDiff <= tolerance -> "Arah sudah tepat"
            diff > 0 -> "Hadap kanan $absDiff derajat"
            else -> "Hadap kiri $absDiff derajat"
        }
    }
    
    // Data classes
    data class TurnInstruction(
        val direction: TurnDirection,
        val degrees: Int
    )
    
    data class NavigationInstruction(
        val turnDirection: TurnDirection,
        val turnDegrees: Int,
        val targetBearing: Double,
        val stepCount: Int,
        val destinationName: String
    )
    
    enum class TurnDirection {
        STRAIGHT, LEFT, RIGHT
    }
}
