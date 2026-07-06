package com.myapplication.common.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable

/**
 * Platform bitmap decode. `org.jetbrains.skia` is available for the desktop
 * (skiko) and iOS targets but NOT for the Android target (Jetpack Compose),
 * so the raw byte→ImageBitmap step lives in per-platform `actual`s while the
 * Base64 handling stays common.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

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
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeHeatmapImage(base64: String): ImageBitmap? {
        return try {
            decodeImageBitmap(Base64.decode(base64))
        } catch (e: Exception) {
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
