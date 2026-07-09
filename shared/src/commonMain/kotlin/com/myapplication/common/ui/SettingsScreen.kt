package com.myapplication.common.ui

import com.myapplication.common.formatTo
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
    // Key the local edit-buffer on viewModel.settings: the ViewModel loads
    // persisted settings ASYNCHRONOUSLY after startup, so a plain
    // `remember { ... }` snapshots the blank defaults — the screen then shows
    // an empty URL even though one is saved, and tapping "Save" writes those
    // blanks over the user's real configuration. Re-seeding when the loaded
    // value arrives fixes both.
    var settings by remember(viewModel.settings) { mutableStateOf(viewModel.settings) }
    // Numeric fields are string-backed while editing: parsing on every
    // keystroke with a `?: default` fallback meant clearing the field
    // instantly snapped it back to the default mid-typing.
    var timeoutText by remember(viewModel.settings) {
        mutableStateOf(viewModel.settings.apiTimeout.toString())
    }
    var cacheCountText by remember(viewModel.settings) {
        mutableStateOf(viewModel.settings.cacheResultsCount.toString())
    }
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
            value = timeoutText,
            onValueChange = { text ->
                timeoutText = text
                text.toLongOrNull()?.let { settings = settings.copy(apiTimeout = it) }
            },
            label = { Text("Timeout (ms)") },
            modifier = Modifier.fillMaxWidth(),
            isError = timeoutText.toLongOrNull() == null,
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
            Text("Confidence Threshold: ${settings.confidenceThreshold.formatTo(2)}")
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
            value = cacheCountText,
            onValueChange = { text ->
                cacheCountText = text
                text.toIntOrNull()?.let { settings = settings.copy(cacheResultsCount = it) }
            },
            label = { Text("Max History Entries") },
            modifier = Modifier.fillMaxWidth(),
            isError = cacheCountText.toIntOrNull() == null,
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
            // Keep the confirmation on screen briefly — resetting on the very
            // next frame made it flash for ~one frame (effectively invisible).
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
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
