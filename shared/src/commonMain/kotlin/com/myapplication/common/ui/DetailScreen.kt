package com.myapplication.common.ui

import com.myapplication.common.formatTo
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
import com.myapplication.common.AppViewModel

@Composable
fun DetailScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val entry = viewModel.selectedHistoryEntry
    val result = viewModel.analysisResult

    if (entry == null || result == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data selected")
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar
        TopAppBar(
            title = { Text(entry.fileName) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    // Share functionality would go here
                }) {
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
                    // Three-band verdict header (matches the live analysis screen).
                    VerdictStatusHeader(result.verdict, result.confidence)

                    Divider()

                    // Metadata
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetadataRow("Analyzed:", entry.formattedTime)
                        MetadataRow("Mode:", entry.analysisMode)
                        MetadataRow("Processing Time:", "${entry.processingTimeMs}ms")
                        MetadataRow("File Size:", entry.formattedSize)
                    }

                    // AI-generated probability bar, coloured by the verdict band.
                    Divider()
                    Text("AI-generated probability", style = MaterialTheme.typography.caption)
                    LinearProgressIndicator(
                        progress = result.confidence,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = result.verdict.visuals().accent
                    )
                }
            }

            // Heatmap Display
            result.heatmapImage?.let { heatmap ->
                Card(modifier = Modifier.fillMaxWidth(), elevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("AI Detection Heatmap", style = MaterialTheme.typography.h6)
                        Image(
                            bitmap = heatmap,
                            contentDescription = "Heatmap",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentScale = ContentScale.Fit
                        )
                        Text(
                            "Red areas indicate likely AI-generated regions",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Detailed Features
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
                        result.detailedFeatures.forEach { (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key, style = MaterialTheme.typography.body2)
                                Text(
                                    value.formatTo(4),
                                    style = MaterialTheme.typography.body2,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
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
                    OutlinedTextField(
                        value = entry.notes,
                        onValueChange = { },
                        placeholder = { Text("Add notes about this analysis...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        enabled = false,
                        maxLines = 5
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.body2)
        Text(
            value,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
        )
    }
}
