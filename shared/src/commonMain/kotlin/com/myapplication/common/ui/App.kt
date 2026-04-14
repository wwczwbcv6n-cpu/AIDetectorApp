package com.myapplication.common.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myapplication.common.AppViewModel
import com.myapplication.common.PyTorchModel
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.data.SettingsRepository

@Composable
fun App(
    pyTorchModel: PyTorchModel,
    settingsRepository: SettingsRepository,
    historyRepository: AnalysisHistoryRepository,
    context: Any
) {
    val viewModel = remember {
        AppViewModel(pyTorchModel, settingsRepository, historyRepository)
    }

    var currentScreen by remember { mutableStateOf(Screen.ANALYSIS) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Main Content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when (currentScreen) {
                            Screen.ANALYSIS -> AnalysisScreen(viewModel)
                            Screen.HISTORY -> HistoryScreen(
                                viewModel,
                                onSelectEntry = { entry ->
                                    viewModel.selectHistoryEntry(entry)
                                    currentScreen = Screen.DETAIL
                                }
                            )
                            Screen.SETTINGS -> SettingsScreen(viewModel)
                            Screen.DETAIL -> DetailScreen(
                                viewModel,
                                onBack = { currentScreen = Screen.HISTORY }
                            )
                        }
                    }

                    // Bottom Navigation (only for main screens)
                    if (currentScreen != Screen.DETAIL) {
                        BottomNavigation(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = MaterialTheme.colors.surface,
                            contentColor = MaterialTheme.colors.primary
                        ) {
                            BottomNavigationItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = "Analysis"
                                    )
                                },
                                label = { Text("Analyze") },
                                selected = currentScreen == Screen.ANALYSIS,
                                onClick = { currentScreen = Screen.ANALYSIS }
                            )
                            BottomNavigationItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = "History"
                                    )
                                },
                                label = { Text("History") },
                                selected = currentScreen == Screen.HISTORY,
                                onClick = { currentScreen = Screen.HISTORY }
                            )
                            BottomNavigationItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings"
                                    )
                                },
                                label = { Text("Settings") },
                                selected = currentScreen == Screen.SETTINGS,
                                onClick = { currentScreen = Screen.SETTINGS }
                            )
                        }
                    }
                }
            }
        }
    }
}
