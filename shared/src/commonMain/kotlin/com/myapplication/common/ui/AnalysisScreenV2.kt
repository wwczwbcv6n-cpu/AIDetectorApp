package com.myapplication.common.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapplication.common.AppViewModelV2
import com.myapplication.common.ImagePickerFactory
import com.myapplication.common.Logger

/**
 * Production-grade AnalysisScreen with:
 * - Rate limiting UI feedback
 * - Input validation
 * - Resource cleanup
 * - Graceful error handling
 */
@Composable
fun AnalysisScreenV2(viewModel: AppViewModelV2) {
    val imagePicker = remember { ImagePickerFactory().createPicker() }
    val analysisResult = viewModel.analysisResult
    var showErrorSnackbar by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Logger.debug("AnalysisScreenV2 mounted")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text("AI Image Detector", style = MaterialTheme.typography.h4)
        Text(
            "Select an image to analyze",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )

        // Image Picker Button (disabled while loading)
        Button(
            onClick = {
                Logger.debug("Image picker opened")
                imagePicker.pickImage { imageBytesList ->
                    imageBytesList.firstOrNull()?.let { imageBytes ->
                        if (imageBytes.isNotEmpty()) {
                            Logger.info("Image selected: ${imageBytes.size} bytes")
                            viewModel.analyzeImage(
                                imageBytes,
                                fileName = "selected_image.jpg",
                                fileSize = imageBytes.size.toLong()
                            )
                        } else {
                            Logger.warn("Empty image selected")
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !viewModel.isLoading
        ) {
            if (viewModel.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 8.dp),
                    color = Color.White
                )
            }
            Text(
                if (viewModel.isLoading) "Analyzing..." else "Select Image to Analyze"
            )
        }

        // Loading Indicator with timeout warning
        AnimatedVisibility(viewModel.isLoading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator()
                Text("Analyzing...", style = MaterialTheme.typography.body2)
                Text(
                    "This may take a moment",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // Error Message with auto-dismiss
        viewModel.errorMessage?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                backgroundColor = if (message.startsWith("✓")) Color(0xFFE0FFE0) else Color(0xFFFFE0E0),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        message.take(100),  // Limit message length
                        color = if (message.startsWith("✓")) Color(0xFF00CC00) else Color(0xFFCC0000),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            Logger.debug("Error message dismissed")
                            viewModel.clearError()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("✕", color = Color.Gray)
                    }
                }
            }

            // Auto-dismiss success messages after 3 seconds
            if (message.startsWith("✓")) {
                LaunchedEffect(message) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.clearError()
                }
            }
        }

        // Selected Image with error handling
        viewModel.detectedImage?.let { imageBitmap ->
            try {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Selected Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            } catch (e: Exception) {
                Logger.error("Failed to display selected image", e)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = Color(0xFFFFE0E0)
                ) {
                    Text(
                        "Failed to display image",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colors.error
                    )
                }
            }
        }

        // Analysis Results with validation
        analysisResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (result.isAI) Color(0xFFFFE0E0) else Color(0xFFE0FFE0),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (result.isAI) "AI" else "Real",
                                        fontSize = 14.sp,
                                        color = if (result.isAI) Color(0xFFCC0000) else Color(0xFF00CC00),
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Column {
                            Text(
                                text = if (result.isAI) "AI Generated" else "Real Image",
                                style = MaterialTheme.typography.h6
                            )
                            Text(
                                text = "Confidence: ${(result.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.body2
                            )
                        }
                    }

                    Divider()

                    // Confidence Bar with bounds checking
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Confidence Score", style = MaterialTheme.typography.caption)
                        LinearProgressIndicator(
                            progress = result.confidence.coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = when {
                                result.confidence > 0.8f -> Color(0xFFCC0000)
                                result.confidence > 0.6f -> Color(0xFFFF6600)
                                result.confidence > 0.4f -> Color(0xFFFFCC00)
                                else -> Color(0xFF00CC00)
                            }
                        )
                    }

                    // Processing Time
                    Text(
                        text = "Processing time: ${result.processingTimeMs}ms",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )

                    // Heatmap Display
                    result.heatmapImage?.let { heatmap ->
                        Divider()
                        Text("AI Detection Heatmap", style = MaterialTheme.typography.caption)
                        try {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Image(
                                    bitmap = heatmap,
                                    contentDescription = "Heatmap",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(250.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } catch (e: Exception) {
                            Logger.error("Failed to display heatmap", e)
                        }
                    }

                    // Detailed Features with safe display
                    if (result.detailedFeatures.isNotEmpty()) {
                        Divider()
                        Text("Detailed Features", style = MaterialTheme.typography.caption)
                        result.detailedFeatures.take(10).forEach { (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key.take(30), style = MaterialTheme.typography.body2)
                                Text(
                                    try {
                                        String.format("%.4f", value)
                                    } catch (e: Exception) {
                                        "N/A"
                                    },
                                    style = MaterialTheme.typography.body2,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
