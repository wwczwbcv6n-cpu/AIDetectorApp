package com.myapplication.common

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class HeuristicResult(
    val isAI: Boolean,
    val confidence: Float,
    val features: Map<String, Float>,
    val processedImage: ImageBitmap?
)

/**
 * Decoded image data ready for analysis.
 *
 * `pixels` is row-major ARGB packed Ints (alpha in top byte). The companion
 * `expect fun decodeImage` returns this; each platform supplies its own
 * decoder (BitmapFactory on Android, UIImage on iOS, ImageIO on JVM).
 */
data class DecodedImage(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val composeImage: ImageBitmap? = null
)

expect fun decodeImage(bytes: ByteArray, maxSide: Int = 256): DecodedImage

/**
 * On-device AI-image detector using simple statistical heuristics.
 *
 * No model file. Pure math. Runs on every platform the app ships to.
 * Each feature returns [0,1] where higher means "more AI-like"; the final
 * confidence is a calibrated weighted sum.
 *
 * This is the v1 detector. When the trained UnifiedFusionNet checkpoint is
 * exported and shipped, the per-feature weights will be replaced with a
 * learned aggregator — same pipeline, smarter combiner.
 */
class HeuristicAIDetector {

    fun analyze(imageData: ByteArray): HeuristicResult {
        val img = decodeImage(imageData, maxSide = 256)
        val n = img.width * img.height
        val luma = FloatArray(n)
        val sat = FloatArray(n)
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val px = img.pixels[i]
            val rr = (px shr 16 and 0xFF) / 255f
            val gg = (px shr 8 and 0xFF) / 255f
            val bb = (px and 0xFF) / 255f
            r[i] = rr; g[i] = gg; b[i] = bb
            luma[i] = 0.299f * rr + 0.587f * gg + 0.114f * bb
            val mx = max(rr, max(gg, bb))
            val mn = min(rr, min(gg, bb))
            sat[i] = if (mx <= 1e-6f) 0f else (mx - mn) / mx
        }

        val features = linkedMapOf(
            "high_freq_deficit" to highFreqDeficit(luma, img.width, img.height),
            "block_variance_uniformity" to blockVarianceUniformity(luma, img.width, img.height),
            "edge_smoothness" to edgeSmoothness(luma, img.width, img.height),
            "saturation_excess" to saturationExcess(sat),
            "channel_decorrelation" to channelDecorrelation(r, g, b),
            "horizontal_symmetry" to horizontalSymmetry(luma, img.width, img.height),
            "histogram_irregularity" to histogramIrregularity(luma)
        )
        val weights = mapOf(
            "high_freq_deficit" to 0.22f,
            "block_variance_uniformity" to 0.18f,
            "edge_smoothness" to 0.16f,
            "saturation_excess" to 0.10f,
            "channel_decorrelation" to 0.10f,
            "horizontal_symmetry" to 0.08f,
            "histogram_irregularity" to 0.16f
        )
        var score = 0f
        var wSum = 0f
        for ((k, v) in features) {
            val w = weights[k] ?: 0f
            score += w * v
            wSum += w
        }
        val confidence = (score / wSum).coerceIn(0f, 1f)

        return HeuristicResult(
            isAI = confidence > 0.5f,
            confidence = confidence,
            features = features,
            processedImage = img.composeImage
        )
    }

    // Diffusion models smooth fine detail. Mean abs Laplacian normalised by
    // mean luminance: real photos sit ~0.05–0.20, AI ~0.01–0.05.
    private fun highFreqDeficit(luma: FloatArray, w: Int, h: Int): Float {
        var meanLum = 0f
        for (v in luma) meanLum += v
        meanLum /= luma.size
        var sumAbs = 0f
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = luma[y * w + x]
                val lap = luma[(y - 1) * w + x] + luma[(y + 1) * w + x] +
                        luma[y * w + x - 1] + luma[y * w + x + 1] - 4f * c
                sumAbs += abs(lap)
                count++
            }
        }
        val energy = sumAbs / count / max(meanLum, 1e-3f)
        return ((0.10f - energy) / 0.10f).coerceIn(0f, 1f)
    }

    // Real photos have texture clumps → block variances vary. AI is uniform.
    private fun blockVarianceUniformity(luma: FloatArray, w: Int, h: Int): Float {
        val block = 16
        val nbx = w / block
        val nby = h / block
        if (nbx == 0 || nby == 0) return 0f
        val variances = FloatArray(nbx * nby)
        for (by in 0 until nby) {
            for (bx in 0 until nbx) {
                var mean = 0f
                val n = block * block
                for (yy in 0 until block) for (xx in 0 until block)
                    mean += luma[(by * block + yy) * w + (bx * block + xx)]
                mean /= n
                var v = 0f
                for (yy in 0 until block) for (xx in 0 until block) {
                    val d = luma[(by * block + yy) * w + (bx * block + xx)] - mean
                    v += d * d
                }
                variances[by * nbx + bx] = v / n
            }
        }
        var m = 0f
        for (v in variances) m += v
        m /= variances.size
        var s = 0f
        for (v in variances) s += (v - m) * (v - m)
        s = sqrt(s / variances.size)
        val cv = if (m > 1e-6f) s / m else 0f
        return (1f - (cv / 1.2f).coerceIn(0f, 1f))
    }

    // Sobel edge magnitudes. AI tends to lower coefficient of variation.
    private fun edgeSmoothness(luma: FloatArray, w: Int, h: Int): Float {
        val n = (w - 2) * (h - 2)
        if (n <= 0) return 0f
        val mags = FloatArray(n)
        var idx = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = luma[(y - 1) * w + (x + 1)] +
                        2 * luma[y * w + (x + 1)] +
                        luma[(y + 1) * w + (x + 1)] -
                        luma[(y - 1) * w + (x - 1)] -
                        2 * luma[y * w + (x - 1)] -
                        luma[(y + 1) * w + (x - 1)]
                val gy = luma[(y + 1) * w + (x - 1)] +
                        2 * luma[(y + 1) * w + x] +
                        luma[(y + 1) * w + (x + 1)] -
                        luma[(y - 1) * w + (x - 1)] -
                        2 * luma[(y - 1) * w + x] -
                        luma[(y - 1) * w + (x + 1)]
                mags[idx++] = sqrt(gx * gx + gy * gy)
            }
        }
        var m = 0f
        for (v in mags) m += v
        m /= mags.size
        var s = 0f
        for (v in mags) s += (v - m) * (v - m)
        s = sqrt(s / mags.size)
        val cv = if (m > 1e-6f) s / m else 0f
        return (1f - (cv / 1.5f).coerceIn(0f, 1f))
    }

    private fun saturationExcess(sat: FloatArray): Float {
        var m = 0f
        for (v in sat) m += v
        m /= sat.size
        return ((m - 0.30f) / 0.30f).coerceIn(0f, 1f)
    }

    // Real cameras have RGB correlation ~0.9+. AI sometimes weaker.
    private fun channelDecorrelation(r: FloatArray, g: FloatArray, b: FloatArray): Float {
        val rg = pearson(r, g)
        val rb = pearson(r, b)
        val gb = pearson(g, b)
        val mean = (rg + rb + gb) / 3f
        return ((0.92f - mean) / 0.30f).coerceIn(0f, 1f)
    }

    private fun pearson(a: FloatArray, b: FloatArray): Float {
        var ma = 0f; var mb = 0f
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= b.size
        var num = 0f; var da = 0f; var db = 0f
        for (i in a.indices) {
            val xa = a[i] - ma
            val xb = b[i] - mb
            num += xa * xb; da += xa * xa; db += xb * xb
        }
        val denom = sqrt(da * db)
        return if (denom < 1e-6f) 1f else (num / denom)
    }

    private fun horizontalSymmetry(luma: FloatArray, w: Int, h: Int): Float {
        var sumDiff = 0f
        var count = 0
        for (y in 0 until h) {
            for (x in 0 until w / 2) {
                val l = luma[y * w + x]
                val rr = luma[y * w + (w - 1 - x)]
                sumDiff += abs(l - rr)
                count++
            }
        }
        val meanDiff = if (count > 0) sumDiff / count else 0f
        return ((0.10f - meanDiff) / 0.10f).coerceIn(0f, 1f)
    }

    private fun histogramIrregularity(luma: FloatArray): Float {
        val bins = IntArray(64)
        for (v in luma) bins[(v * 64f).toInt().coerceIn(0, 63)]++
        var totalDelta = 0f
        for (i in 1 until bins.size) totalDelta += abs((bins[i] - bins[i - 1]).toFloat())
        val roughness = totalDelta / luma.size
        return (roughness / 0.25f).coerceIn(0f, 1f)
    }
}
