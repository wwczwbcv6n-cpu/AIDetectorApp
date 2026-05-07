package com.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapplication.common.data.AIDetectorApi
import com.myapplication.common.data.AnalysisResult
import com.myapplication.common.data.AppSettings
import com.myapplication.common.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {

    // Lazy: only constructed once we have an Android Context (post-onCreate).
    // The ctor was previously bare `AIDetectorApi()` which used a hardcoded
    // LAN IP — now we read the user-configured base URL + API key from
    // SettingsRepository, with a non-functional fallback that surfaces a
    // clear error to the user instead of silently reaching out to a
    // developer's laptop.
    private val settingsRepo by lazy { SettingsRepository(this) }
    private var settings: AppSettings = AppSettings()
    private var api: AIDetectorApi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageUri = getUriFromIntent(intent)

        setContent {
            MaterialTheme {
                ShareScreen(imageUri)
            }
        }
    }

    private fun getUriFromIntent(intent: Intent): Uri? {
        return if (intent.action == Intent.ACTION_SEND) {
            (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        } else {
            null
        }
    }

    @Composable
    fun ShareScreen(uri: Uri?) {
        var result by remember { mutableStateOf<Result<AnalysisResult>?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var configError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(uri) {
            if (uri != null) {
                isLoading = true
                CoroutineScope(Dispatchers.IO).launch {
                    // Load settings first; refuse to fire if the user
                    // hasn't configured a server.
                    settings = settingsRepo.getSettings()
                    if (!settings.isApiUrlAcceptable()) {
                        withContext(Dispatchers.Main) {
                            configError = "Set API Base URL in app Settings " +
                                "before using Share-to-AI-Detector."
                            isLoading = false
                        }
                        return@launch
                    }
                    if (api == null) {
                        api = AIDetectorApi(
                            baseUrl = settings.apiBaseUrl,
                            apiKey = settings.apiKey.takeIf { it.isNotBlank() },
                        )
                    }

                    val imageData = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val analysisResult = imageData?.let { api?.analyzeImage(it) }

                    withContext(Dispatchers.Main) {
                        result = analysisResult
                        isLoading = false
                    }
                }
            }
        }
        configError?.let { msg ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(msg, color = Color.Red, fontSize = 18.sp)
            }
            return
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Analyzing...", fontSize = 18.sp)
                    }
                }
                result != null -> {
                    result!!.fold(
                        onSuccess = {
                            ResultView(it)
                        },
                        onFailure = {
                            Text("Error: ${it.message}", color = Color.Red, fontSize = 18.sp)
                        }
                    )
                }
                uri == null -> {
                    Text("No image shared.", fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    fun ResultView(analysisResult: AnalysisResult) {
        val isAiGenerated = analysisResult.conclusion.equals("AI-Generated", ignoreCase = true)
        val color = if (isAiGenerated) Color(0xFFF44336) else Color(0xFF4CAF50) // Red for AI, Green for REAL

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = analysisResult.conclusion,
                fontSize = 32.sp,
                color = color,
                style = MaterialTheme.typography.h4
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "AI Probability: %.2f%%".format(analysisResult.aiProbability * 100),
                fontSize = 18.sp,
                color = Color.Gray
            )
        }
    }
}
