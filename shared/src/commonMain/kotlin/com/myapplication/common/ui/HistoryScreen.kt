package com.myapplication.common.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapplication.common.AppViewModel
import com.myapplication.common.data.AnalysisHistoryEntry

@Composable
fun HistoryScreen(
    viewModel: AppViewModel,
    onSelectEntry: (AnalysisHistoryEntry) -> Unit
) {
    val history = viewModel.analysisHistory
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var selectedForDelete by remember { mutableStateOf<AnalysisHistoryEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Text(
            "Analysis History",
            style = MaterialTheme.typography.h4,
            modifier = Modifier.padding(16.dp)
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                if (it.isEmpty()) {
                    viewModel.loadHistory()
                } else {
                    viewModel.searchHistory(it)
                }
            },
            label = { Text("Search history...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Clear History Button
        if (history.isNotEmpty()) {
            Button(
                // Deleting the whole history is irreversible — confirm first
                // (single-entry delete already did; Clear All didn't).
                onClick = { showClearAllDialog = true },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error
                )
            ) {
                Text("Clear All", color = Color.White)
            }
        }

        Divider()

        // History List
        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No analysis history yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { entry ->
                    HistoryEntryCard(
                        entry = entry,
                        onSelect = onSelectEntry,
                        onDelete = {
                            selectedForDelete = entry
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    // Clear-All Confirmation Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All History") },
            text = { Text("Delete all ${history.size} analysis results? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearHistory()
                        showClearAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error
                    )
                ) {
                    Text("Delete All", color = Color.White)
                }
            },
            dismissButton = {
                Button(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog && selectedForDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Entry") },
            text = { Text("Are you sure you want to delete this analysis result?") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedForDelete?.id?.let { viewModel.deleteHistoryEntry(it) }
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun HistoryEntryCard(
    entry: AnalysisHistoryEntry,
    onSelect: (AnalysisHistoryEntry) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(entry) }
            .padding(4.dp),
        elevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Three-band verdict indicator (matches the analysis + detail screens).
            VerdictBadge(entry.verdictBand)

            // Entry Details
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.subtitle2
                )
                Text(
                    text = entry.formattedTime,
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // The server's label + "N% confident" (ai/real only); never
                    // the stored raw p_ai (audit 2026-09-24 APP-02).
                    val p = entry.presentation()
                    Text(
                        text = listOfNotNull(p.headline, p.confidenceLine ?: p.mixLine)
                            .joinToString(" · "),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${entry.processingTimeMs}ms",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Delete Button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colors.error
                )
            }
        }
    }
}
