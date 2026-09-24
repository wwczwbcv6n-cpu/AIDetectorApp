package com.myapplication

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myapplication.common.LOCAL_PROVENANCE_LABEL
import com.myapplication.common.MetadataAnalyzer
import com.myapplication.common.PickedImage
import com.myapplication.common.ShareRequestGuard
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.data.ApiAnalysisResult
import com.myapplication.common.data.ApiClient
import com.myapplication.common.data.ApiError
import com.myapplication.common.data.ApiException
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.nowMillis
import com.myapplication.common.provenanceHistoryEntry
import com.myapplication.common.readPickedImage
import com.myapplication.common.secure.TierState
import com.myapplication.common.serverHistoryEntry
import com.myapplication.common.sha256Hex
import com.myapplication.common.ui.TierStateLine
import com.myapplication.common.ui.VerdictStatusHeader
import com.myapplication.common.ui.presentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the share-sheet analysis across Activity recreation (audit 2026-09-24
 * APP-16). The old ShareActivity kept its state in plain `remember` inside
 * LaunchedEffect(uri) and declared no configChanges, so rotating during
 * "Analyzing…" destroyed it (closing the client) and re-ran the upload — a
 * second billed call on the key. The request now runs in viewModelScope,
 * started once per shared uri by a [ShareRequestGuard] this retained
 * ViewModel owns; the client lives as long as the ViewModel.
 */
class ShareViewModel(app: Application) : AndroidViewModel(app) {
    private val guard = ShareRequestGuard()
    private var api: ApiClient? = null

    var isLoading by mutableStateOf(false)
        private set
    var result by mutableStateOf<Result<ApiAnalysisResult>?>(null)
        private set
    var configError by mutableStateOf<String?>(null)
        private set
    /** The tier line the main screen shows too ("Standard tier: ..."). */
    var tierState by mutableStateOf<TierState?>(null)
        private set

    fun start(uri: Uri) {
        if (!guard.shouldStart(uri.toString())) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            isLoading = true
            try {
                val settings = withContext(Dispatchers.IO) { SettingsRepository(ctx).getSettings() }
                if (!settings.isApiUrlAcceptable()) {
                    configError = "Set the API Base URL in Settings before using Share-to-AI-Detector."
                    return@launch
                }
                val client = api ?: ApiClient(settings).also { c ->
                    c.onTierState = { s -> tierState = s }
                    api = c
                }
                val started = nowMillis()
                val picked = withContext(Dispatchers.IO) { readPickedImage(ctx, uri) }
                val image = (picked as? PickedImage.Picked)
                if (image == null) {
                    val msg = (picked as? PickedImage.Refused)?.message ?: "Couldn't read the shared image."
                    result = Result.failure(ApiException(ApiError.ClientError(413, msg)))
                    return@launch
                }
                val name = image.displayName ?: "shared image"
                val hash = withContext(Dispatchers.Default) { sha256Hex(image.bytes) }
                val history = AnalysisHistoryRepository(ctx)
                // Provenance first (research §7): a file that confesses its own
                // generation is decided locally and never uploaded (PROV-7).
                val meta = withContext(Dispatchers.Default) {
                    try { MetadataAnalyzer.analyze(image.bytes) } catch (e: Exception) { null }
                }
                if (meta?.generatorMatch != null) {
                    result = Result.success(ApiAnalysisResult(
                        conclusion = "AI-Generated",
                        verdict = "ai",
                        label = LOCAL_PROVENANCE_LABEL,
                        detail = "AI generator signature in metadata: ${meta.generatorMatch}",
                        method = "provenance",
                    ))
                    history.addEntry(provenanceHistoryEntry(name, image.bytes.size.toLong(), nowMillis() - started, hash))
                    return@launch
                }
                val r = client.analyzeImage(image.bytes)
                result = r
                // Share results are history too (APP-16): same row as the main screen.
                r.getOrNull()?.let {
                    history.addEntry(serverHistoryEntry(it, name, image.bytes.size.toLong(), nowMillis() - started, hash))
                }
            } finally {
                isLoading = false
            }
        }
    }

    override fun onCleared() {
        try { api?.close() } catch (e: Exception) { /* best-effort */ }
        api = null
        super.onCleared()
    }
}

/**
 * Share-sheet entry point — the product's primary, policy-safe surface (user
 * taps Share in any app → picks us → we analyze). Renders the same result
 * card as the in-app screens (ui/ResultPresentation.kt) plus the tier line.
 */
class ShareActivity : ComponentActivity() {

    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageUri = getUriFromIntent(intent)
        imageUri?.let { vm.start(it) }
        setContent {
            MaterialTheme {
                ShareScreen(imageUri)
            }
        }
    }

    private fun getUriFromIntent(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        // IntentCompat: the typed lookup on every API level (the platform's
        // typed overload is unreliable on API 33), and a non-Uri extra planted
        // by a malicious sender comes back null instead of throwing.
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    @Composable
    fun ShareScreen(uri: Uri?) {
        vm.configError?.let { msg ->
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(msg, color = Color(0xFFCC0000), fontSize = 18.sp)
            }
            return
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            vm.tierState?.let { TierStateLine(it) }
            val result = vm.result
            when {
                vm.isLoading -> {
                    CircularProgressIndicator()
                    Text("Analyzing…", fontSize = 18.sp)
                }
                result != null -> {
                    // Plain if/else rather than Result.fold: fold's lambdas are
                    // not @Composable.
                    val success = result.getOrNull()
                    if (success != null) {
                        ResultView(success)
                    } else {
                        val e = result.exceptionOrNull()
                        val msg = (e as? ApiException)?.apiError?.userMessage
                            ?: e?.message ?: "Analysis failed."
                        Text(msg, color = Color(0xFFCC0000), fontSize = 18.sp)
                    }
                }
                uri == null -> Text("No image shared.", fontSize = 18.sp)
            }
        }
    }

    @Composable
    fun ResultView(analysisResult: ApiAnalysisResult) {
        val verdict = analysisResult.toVerdict()
        Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The server's label, "N% confident" on ai/real, the mix shares on
                // uncertain — never the raw p_ai (audit 2026-09-24 APP-02).
                VerdictStatusHeader(presentResult(verdict, analysisResult.label,
                    analysisResult.confidence, analysisResult.mix?.toSummary()))
                analysisResult.detail?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f)
                    )
                }
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
