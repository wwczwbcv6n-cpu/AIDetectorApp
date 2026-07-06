package com.myapplication.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapplication.common.data.Verdict

/**
 * Per-band presentation: short chip text, an honest headline, a one-line
 * explanation, and colours. Copy deliberately says "likely" / "possibly" and
 * never renders a bare "FAKE" — an individual verdict is probabilistic and we
 * must not overclaim (both a trust and a defamation-exposure concern).
 */
data class VerdictVisuals(
    val badge: String,
    val title: String,
    val description: String,
    val accent: Color,
    val container: Color,
)

fun Verdict.visuals(): VerdictVisuals = when (this) {
    Verdict.AUTHENTIC -> VerdictVisuals(
        badge = "REAL",
        title = "Likely authentic",
        description = "No strong signs of AI generation — this looks like a real photo.",
        accent = Color(0xFF2E7D32),
        container = Color(0xFFE6F4EA),
    )
    Verdict.AI -> VerdictVisuals(
        badge = "AI",
        title = "Likely AI-generated",
        description = "Strong signs this image was generated or heavily edited by AI.",
        accent = Color(0xFFC62828),
        container = Color(0xFFFCE4E4),
    )
    Verdict.UNCERTAIN -> VerdictVisuals(
        badge = "?",
        title = "Uncertain",
        description = "Not enough signal to call this confidently — treat with caution.",
        accent = Color(0xFF8D6E00),
        container = Color(0xFFFFF6DB),
    )
    Verdict.TAMPERED -> VerdictVisuals(
        badge = "EDIT",
        title = "Possibly edited",
        description = "Signs of localized editing or manipulation in part of the image.",
        accent = Color(0xFF6A1B9A),
        container = Color(0xFFF3E5F5),
    )
}

/** Small coloured square chip — used in result headers and the history list. */
@Composable
fun VerdictBadge(verdict: Verdict, size: Int = 48) {
    val v = verdict.visuals()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = v.container,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = v.badge,
                fontSize = (size / 3.4f).sp,
                color = v.accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The canonical result header: badge + honest title + calibrated
 * AI-generated probability + a one-line explanation. Shared by the live
 * analysis screen, the history detail screen, and the share-sheet result so
 * all three read identically.
 *
 * @param aiProbability calibrated P(AI-generated) in 0..1.
 */
@Composable
fun VerdictStatusHeader(verdict: Verdict, aiProbability: Float) {
    val v = verdict.visuals()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VerdictBadge(verdict)
            Column {
                Text(text = v.title, style = MaterialTheme.typography.h6, color = v.accent)
                Text(
                    text = "AI-generated probability: ${(aiProbability * 100).toInt()}%",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        Text(
            text = v.description,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
}
