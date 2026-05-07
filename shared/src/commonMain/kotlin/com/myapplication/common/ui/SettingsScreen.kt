package com.myapplication.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapplication.common.AppViewModel
import com.myapplication.common.data.AppSettings

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    var settings by remember { mutableStateOf(viewModel.settings) }
    val isSaving = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.h4)

        // API Configuration Section
        Text("API Configuration", style = MaterialTheme.typography.h6)

        // Material 2 doesn't have `supportingText`; we surface the
        // cleartext-warning as a separate Text below the field.
        val urlError = settings.apiBaseUrl.isNotBlank() &&
                !settings.isApiUrlAcceptable()
        OutlinedTextField(
            value = settings.apiBaseUrl,
            onValueChange = { settings = settings.copy(apiBaseUrl = it) },
            label = { Text("API Base URL") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.example.com") },
            isError = urlError,
            singleLine = true,
        )
        if (urlError) {
            Text(
                "Use https://… (cleartext http only allowed on localhost / LAN)",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
            )
        }

        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = { settings = settings.copy(apiKey = it.trim()) },
            label = { Text("API Key (X-API-Key)") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("paste the key your server admin issued") },
            singleLine = true,
        )

        OutlinedTextField(
            value = settings.apiTimeout.toString(),
            onValueChange = {
                settings = settings.copy(apiTimeout = it.toLongOrNull() ?: 30000L)
            },
            label = { Text("Timeout (ms)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Button(
            onClick = { viewModel.testApiConnection() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test API Connection")
        }

        Divider()

        // Analysis Settings
        Text("Analysis Settings", style = MaterialTheme.typography.h6)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Confidence Threshold: ${String.format("%.2f", settings.confidenceThreshold)}")
            Spacer(modifier = Modifier.weight(1f))
        }

        Slider(
            value = settings.confidenceThreshold,
            onValueChange = { settings = settings.copy(confidenceThreshold = it) },
            valueRange = 0f..1f,
            steps = 9,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = settings.enableHeatmap,
                onCheckedChange = { settings = settings.copy(enableHeatmap = it) }
            )
            Text("Enable Heatmap Visualization")
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = settings.enableLogging,
                onCheckedChange = { settings = settings.copy(enableLogging = it) }
            )
            Text("Enable Debug Logging")
        }

        Divider()

        // Analysis Mode Selection
        Text("Analysis Mode", style = MaterialTheme.typography.h6)

        AppSettings.AnalysisMode.values().forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                RadioButton(
                    selected = settings.analysisMode == mode,
                    onClick = { settings = settings.copy(analysisMode = mode) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(mode.name)
                    Text(
                        when (mode) {
                            AppSettings.AnalysisMode.FAST -> "Quick analysis, lower accuracy"
                            AppSettings.AnalysisMode.BALANCED -> "Balanced speed and quality"
                            AppSettings.AnalysisMode.DETAILED -> "Detailed analysis with heatmap"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        Divider()

        // Cache Settings
        Text("Cache Settings", style = MaterialTheme.typography.h6)

        OutlinedTextField(
            value = settings.cacheResultsCount.toString(),
            onValueChange = {
                settings = settings.copy(cacheResultsCount = it.toIntOrNull() ?: 100)
            },
            label = { Text("Max History Entries") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    viewModel.updateSettings(settings)
                    isSaving.value = true
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Save Settings")
            }

            Button(
                onClick = {
                    settings = AppSettings()
                    viewModel.updateSettings(AppSettings())
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Reset Defaults")
            }
        }

        if (isSaving.value) {
            LaunchedEffect(Unit) {
                isSaving.value = false
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp)
            ) {
                Text("Settings saved!")
            }
        }
    }
}
