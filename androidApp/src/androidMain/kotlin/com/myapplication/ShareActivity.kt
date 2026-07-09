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
import com.myapplication.common.data.ApiAnalysisResult
import com.myapplication.common.data.ApiClient
import com.myapplication.common.data.ApiError
import com.myapplication.common.data.ApiException
import com.myapplication.common.data.AppSettings
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.ui.VerdictStatusHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Share-sheet entry point — the product's primary, policy-safe surface (user
 * taps Share in any app → picks us → we analyze). This now uses [ApiClient]
 * (typed errors + the calibrated 3-way `verdict`) and renders the same honest
 * three-band result as the in-app screens, instead of the old binary red/green
 * stamp that turned an "uncertain" server call into a false accusation.
 */
class ShareActivity : ComponentActivity() {

    // Lazy: only constructed once we have an Android Context (post-onCreate).
    private val settingsRepo by lazy { SettingsRepository(this) }
    private var settings: AppSettings = AppSettings()
    private var api: ApiClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageUri = getUriFromIntent(intent)

        setContent {
            MaterialTheme {
                ShareScreen(imageUri)
            }
        }
    }

    override fun onDestroy() {
        // Release the HttpClient created for this share session so its engine /
        // connection pool doesn't outlive the Activity.
        try {
            api?.close()
        } catch (e: Exception) {
            // Best-effort cleanup; never crash teardown.
        }
        api = null
        super.onDestroy()
    }

    private fun getUriFromIntent(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        // getParcelableExtra(String) is deprecated from API 33; the typed
        // overload also hardens against a non-Uri extra planted by a
        // malicious sender (returns null instead of ClassCastException).
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        }
    }

    @Composable
    fun ShareScreen(uri: Uri?) {
        var result by remember { mutableStateOf<Result<ApiAnalysisResult>?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var configError by remember { mutableStateOf<String?>(null) }

        // Run the analysis inside the LaunchedEffect's own coroutine, which is
        // tied to this composition's lifecycle and cancelled automatically when
        // the Activity is destroyed.
        LaunchedEffect(uri) {
            if (uri != null) {
                isLoading = true
                // Load settings first; refuse to fire if the user hasn't
                // configured a server.
                settings = withContext(Dispatchers.IO) { settingsRepo.getSettings() }
                if (!settings.isApiUrlAcceptable()) {
                    configError = "Set the API Base URL in Settings before " +
                        "using Share-to-AI-Detector."
                    isLoading = false
                    return@LaunchedEffect
                }
                if (api == null) api = ApiClient(settings)

                result = withContext(Dispatchers.IO) {
                    val imageData = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (imageData == null) {
                        Result.failure(ApiException(ApiError.Unknown("Couldn't read the shared image.")))
                    } else {
                        api!!.analyzeImage(imageData)
                    }
                }
                isLoading = false
            }
        }

        configError?.let { msg ->
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(msg, color = Color(0xFFCC0000), fontSize = 18.sp)
            }
            return
        }

        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Analyzing…", fontSize = 18.sp)
                    }
                }
                result != null -> {
                    // Unwrap with plain if/else rather than Result.fold: fold's
                    // lambdas are not @Composable, so composable calls aren't
                    // allowed inside them.
                    val success = result!!.getOrNull()
                    if (success != null) {
                        ResultView(success)
                    } else {
                        val e = result!!.exceptionOrNull()
                        val msg = (e as? ApiException)?.apiError?.userMessage
                            ?: e?.message ?: "Analysis failed."
                        Text(msg, color = Color(0xFFCC0000), fontSize = 18.sp)
                    }
                }
                uri == null -> {
                    Text("No image shared.", fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    fun ResultView(analysisResult: ApiAnalysisResult) {
        val verdict = analysisResult.toVerdict(settings.confidenceThreshold)
        Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VerdictStatusHeader(verdict, analysisResult.uiConfidence)
                Divider()
                Text(
                    text = "This is a probabilistic estimate and can be wrong.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
