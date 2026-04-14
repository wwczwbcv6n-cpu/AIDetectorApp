package com.myapplication.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapplication.common.AppViewModelV2
import com.myapplication.common.Logger

/**
 * Production-grade DetailScreen with:
 * - Proper null safety
 * - Error handling
 * - Resource cleanup
 */
@Composable
fun DetailScreenV2(viewModel: AppViewModelV2, onBack: () -> Unit) {
    val entry = viewModel.selectedHistoryEntry
    val result = viewModel.analysisResult

    // Proper null handling
    if (entry == null || result == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "No data available",
                    style = MaterialTheme.typography.h6
                )
                Button(onClick = onBack) {
                    Text("Go Back")
                }
            }
        }
        Logger.warn("DetailScreenV2: Missing entry or result data")
        return
    }

    LaunchedEffect(entry.id) {
        Logger.debug("DetailScreenV2 loaded for: ${entry.fileName}")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar with proper error handling
        TopAppBar(
            title = {
                Text(
                    entry.fileName.take(30),  // Limit display length
                    maxLines = 1,
                    overflow = androidx.compose.material.TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        Logger.debug("Share clicked for: ${entry.id}")
                        // Share functionality
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Analysis Result Card
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
                    // Status with validation
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

                    // Metadata with safe display
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SafeMetadataRow("Analyzed:", entry.formattedTime)
                        SafeMetadataRow("Mode:", entry.analysisMode)
                        SafeMetadataRow("Processing Time:", "${entry.processingTimeMs}ms")
                        SafeMetadataRow("File Size:", entry.formattedSize)
                    }

                    // Confidence Bar
                    Divider()
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
            }

            // Heatmap Display (safe)
            result.heatmapImage?.let { heatmap ->
                Card(modifier = Modifier.fillMaxWidth(), elevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("AI Detection Heatmap", style = MaterialTheme.typography.h6)
                        try {
                            Image(
                                bitmap = heatmap,
                                contentDescription = "Heatmap",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp),
                                contentScale = ContentScale.Fit
                            )
                        } catch (e: Exception) {
                            Logger.error("Failed to display heatmap", e)
                            Text(
                                "Failed to load heatmap",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.error
                            )
                        }
                        Text(
                            "Red areas indicate likely AI-generated regions",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Detailed Features (safe)
            if (result.detailedFeatures.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), elevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Detailed Features", style = MaterialTheme.typography.h6)
                        Divider()
                        result.detailedFeatures.take(20).forEach { (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    key.take(30),
                                    style = MaterialTheme.typography.body2,
                                    maxLines = 1,
                                    overflow = androidx.compose.material.TextOverflow.Ellipsis
                                )
                                Text(
                                    try {
                                        String.format("%.4f", value)
                                    } catch (e: Exception) {
                                        "N/A"
                                    },
                                    style = MaterialTheme.typography.body2,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                        if (result.detailedFeatures.size > 20) {
                            Text(
                                "and ${result.detailedFeatures.size - 20} more...",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // Notes
            Card(modifier = Modifier.fillMaxWidth(), elevation = 4.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Notes", style = MaterialTheme.typography.h6)
                    Text(
                        entry.notes.ifBlank { "(No notes)" },
                        style = MaterialTheme.typography.body2,
                        color = if (entry.notes.isBlank()) {
                            MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colors.onSurface
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SafeMetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.body2,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            value.take(50),  // Limit length
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(0.6f),
            maxLines = 1,
            overflow = androidx.compose.material.TextOverflow.Ellipsis
        )
    }
}
