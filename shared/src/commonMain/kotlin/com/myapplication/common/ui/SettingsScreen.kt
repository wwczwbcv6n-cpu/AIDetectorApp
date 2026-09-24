package com.myapplication.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
            onValueChange = {
                settings = settings.copy(apiBaseUrl = it)
                viewModel.clearConnectionTest()
            },
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
            onValueChange = {
                settings = settings.copy(apiKey = it.trim())
                viewModel.clearConnectionTest()
            },
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

        // Tests the values typed above (not the saved ones) and shows the
        // result right here (audit 2026-09-24 APP-04).
        Button(
            onClick = {
                viewModel.testApiConnection(
                    settings.copy(apiTimeout = timeoutText.toLongOrNull() ?: settings.apiTimeout))
            },
            enabled = !viewModel.isTestingConnection,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (viewModel.isTestingConnection) "Testing…" else "Test API Connection")
        }
        viewModel.connectionTestMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.body2,
                color = if (viewModel.connectionTestOk) Color(0xFF2E7D32) else MaterialTheme.colors.error,
            )
        }

        Divider()

        // The threshold slider, heatmap toggle, analysis mode and history size
        // were removed: none of them changed anything (audit 2026-09-24 APP-05).
        Text("Diagnostics", style = MaterialTheme.typography.h6)

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

        // Privacy tier (SPEC §6). Default off = standard tier allowed, with the
        // honest sentence shown on the analysis screen; on = fail closed unless
        // the server's evidence verifies against the pinned policy.
        Text("Privacy", style = MaterialTheme.typography.h6)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = settings.requireAttestation,
                onCheckedChange = { settings = settings.copy(requireAttestation = it) }
            )
            Text("Require the attested tier")
        }
        Text(
            if (settings.requireAttestation) {
                "Uploads happen only after the server proves a pinned software measurement " +
                    "(hardware-attested). If it cannot, nothing is sent."
            } else {
                "Standard tier allowed: Tayanch and its hosting provider can technically " +
                    "read this file during analysis. Turn on to refuse that."
            },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
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
