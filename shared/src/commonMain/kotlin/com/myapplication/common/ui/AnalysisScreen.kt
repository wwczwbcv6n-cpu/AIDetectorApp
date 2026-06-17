package com.myapplication.common.ui

import com.myapplication.common.formatTo
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
import com.myapplication.common.AppViewModel
import com.myapplication.common.ImagePickerFactory

@Composable
fun AnalysisScreen(viewModel: AppViewModel) {
    val imagePicker = ImagePickerFactory().createPicker()
    val analysisResult = viewModel.analysisResult

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

        // Image Picker Button — disabled while a request is in flight (#5) so a
        // double-tap can't enqueue a second overlapping analysis. The ViewModel
        // also guards against this, but disabling gives the user clear feedback.
        Button(
            onClick = {
                imagePicker.pickImage { imageBytesList ->
                    imageBytesList.firstOrNull()?.let { imageBytes ->
                        viewModel.analyzeImage(
                            imageBytes,
                            fileName = "selected_image.jpg",
                            fileSize = imageBytes.size.toLong()
                        )
                    }
                }
            },
            enabled = !viewModel.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (viewModel.isLoading) "Analyzing…" else "Select Image to Analyze")
        }

        // Loading Indicator
        AnimatedVisibility(viewModel.isLoading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator()
                Text("Analyzing...", style = MaterialTheme.typography.body2)
            }
        }

        // Error Message
        viewModel.errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (message.startsWith("✓")) Color(0xFFE0FFE0) else Color(0xFFFFE0E0)
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(12.dp),
                    color = if (message.startsWith("✓")) Color(0xFF00CC00) else Color(0xFFCC0000)
                )
            }
        }

        // Selected Image
        viewModel.detectedImage?.let { imageBitmap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Selected Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Analysis Results
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

                    // Confidence Bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Confidence Score", style = MaterialTheme.typography.caption)
                        LinearProgressIndicator(
                            progress = result.confidence,
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
                    }

                    // Detailed Features
                    if (result.detailedFeatures.isNotEmpty()) {
                        Divider()
                        Text("Detailed Features", style = MaterialTheme.typography.caption)
                        result.detailedFeatures.forEach { (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key, style = MaterialTheme.typography.body2)
                                Text(
                                    value.formatTo(4),
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
