package com.myapplication.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SharedViewModel(
    private val pyTorchModel: PyTorchModel, // Pass the platform-specific PyTorchModel
) {
    var detectedImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var detectionResult by mutableStateOf<DetectionResult?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val aiDetector = AIDetector(pyTorchModel)
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    fun analyzeImage(imageData: ByteArray) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            detectionResult = null
            detectedImage = null
            try {
                // Perform the detection
                val result = aiDetector.analyzeImage(imageData)
                detectionResult = result
                detectedImage = result.processedImage
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Error during analysis: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
}
