package com.myapplication.common

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.IRect
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.impl.toByteArray
import kotlinx.cinterop.*
import org.jetbrains.skia.Canvas

data class DetectionResult(
    val isAI: Boolean,
    val confidence: Float,
    val processedImage: ImageBitmap? = null // For displaying in UI, maybe with highlights
)

// This class will orchestrate the AI detection
class AIDetector(
    private val pyTorchModel: PyTorchModel
) {
    // These constants should match the model's expected input
    private val IMG_WIDTH = 512
    private val IMG_HEIGHT = 512
    private val NORM_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val NORM_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun analyzeImage(imageData: ByteArray): DetectionResult {
        // 1. Convert ByteArray to an Image suitable for processing
        val skiaImage = Image.makeFromEncoded(imageData)
        val skiaBitmap = skiaImage.toBitmap()
        
        // 2. Resize and preprocess the image to model input size
        val resizedBitmap = resizeBitmap(skiaBitmap, IMG_WIDTH, IMG_HEIGHT)
        val floatInput = bitmapToFloatArray(resizedBitmap)

        // 3. Normalize the input (already done in bitmapToFloatArray)

        // 4. Perform inference
        val inputShape = longArrayOf(1, 3, IMG_HEIGHT.toLong(), IMG_WIDTH.toLong())
        val output = pyTorchModel.predict(floatInput, inputShape)

        // 5. Interpret the output
        // The model outputs a single float probability (0 to 1)
        val confidence = output.firstOrNull() ?: 0.0f
        val isAI = confidence > 0.5f // Threshold for AI detection

        // TODO: For "visual corrupted areas", the model would need to output a heatmap or mask.
        // This would require model modification and a more complex post-processing step.
        // For now, we return the resized image.
        return DetectionResult(isAI, confidence, resizedBitmap.toComposeImageBitmap())
    }

    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        // Create a new bitmap with the desired dimensions
        val resizedBitmap = Bitmap().apply {
            installPixels(
                ImageInfo.make(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                null, 0
            )
        }
        val canvas = Canvas(resizedBitmap)
        // Draw the original bitmap scaled onto the new bitmap
        canvas.drawImageRect(
            Image.makeFromBitmap(bitmap),
            IRect.makeWH(bitmap.width, bitmap.height),
            IRect.makeWH(width, height)
        )
        return resizedBitmap
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.readPixels(pixels, 0, width, 0, 0, width, height)

        val floatArray = FloatArray(3 * width * height) // C, H, W layout

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16 and 0xFF) / 255.0f
                val g = (pixel shr 8 and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f

                // Apply normalization and store in C, H, W order
                floatArray[0 * width * height + y * width + x] = (r - NORM_MEAN[0]) / NORM_STD[0] // Red channel
                floatArray[1 * width * height + y * width + x] = (g - NORM_MEAN[1]) / NORM_STD[1] // Green channel
                floatArray[2 * width * height + y * width + x] = (b - NORM_MEAN[2]) / NORM_STD[2] // Blue channel
            }
        }
        return floatArray
    }
}
