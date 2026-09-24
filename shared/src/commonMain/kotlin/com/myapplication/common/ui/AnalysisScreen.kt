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
import com.myapplication.common.secure.TierState

/**
 * The one tier line every client must show (SPEC §6). The copy comes from
 * [TierState.userLine] and is deliberately literal: the standard tier says
 * the operator and its host can technically read the file; only a VERIFIED
 * measurement earns anything stronger, and FAILED says nothing was sent.
 */
@Composable
private fun TierStateLine(state: TierState) {
    val background = when (state) {
        is TierState.Verified -> Color(0xFFE0FFE0)
        TierState.Unattested -> Color(0xFFFFF4D6)
        is TierState.Failed -> Color(0xFFFFE0E0)
    }
    val foreground = when (state) {
        is TierState.Verified -> Color(0xFF1B5E20)
        TierState.Unattested -> Color(0xFF7A4F00)
        is TierState.Failed -> Color(0xFFCC0000)
    }
    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = background) {
        Text(
            state.userLine,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.caption,
            color = foreground
        )
    }
}

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

        // Which tier the last upload went to — verified / standard / failed.
        viewModel.tierState?.let { TierStateLine(it) }

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
                    // Three-band verdict header — honest copy, explicit
                    // "uncertain" band, never a bare binary "FAKE" stamp.
                    VerdictStatusHeader(presentResult(result.verdict, result.label, result.confidencePct?.toDouble(), result.mix))

                    // Qualifier from the pipeline: server degradation abstain
                    // ("share the original file"), provenance match, or cache.
                    result.detailNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f)
                        )
                    }

                    Divider()

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
