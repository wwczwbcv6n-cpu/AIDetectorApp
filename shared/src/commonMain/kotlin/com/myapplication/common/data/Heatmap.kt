package com.myapplication.common.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image
import java.util.Base64

@Serializable
data class HeatmapData(
    val base64Image: String,
    val confidenceMap: List<List<Float>>? = null, // Optional: raw confidence values
    val aiRegions: List<AIRegion> = emptyList()
)

@Serializable
data class AIRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float
)

/**
 * Utility to work with heatmaps
 */
object HeatmapUtils {
    /**
     * Decode base64 heatmap image to ImageBitmap
     */
    fun decodeHeatmapImage(base64: String): ImageBitmap? {
        return try {
            val imageData = Base64.getDecoder().decode(base64)
            val skiaImage = Image.makeFromEncoded(imageData)
            skiaImage.toComposeImageBitmap()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get color for confidence score (red = AI, green = real)
     */
    fun getConfidenceColor(confidence: Float): Color {
        return when {
            confidence > 0.8f -> Color(0xFFFF0000) // Bright red - definitely AI
            confidence > 0.6f -> Color(0xFFFF5500) // Orange - likely AI
            confidence > 0.4f -> Color(0xFFFFFF00) // Yellow - uncertain
            confidence > 0.2f -> Color(0xFF55FF00) // Light green - likely real
            else -> Color(0xFF00FF00)              // Bright green - definitely real
        }
    }

    /**
     * Convert confidence to visual intensity (0.0-1.0)
     */
    fun getConfidenceIntensity(confidence: Float): Float {
        return confidence.coerceIn(0f, 1f)
    }
}
