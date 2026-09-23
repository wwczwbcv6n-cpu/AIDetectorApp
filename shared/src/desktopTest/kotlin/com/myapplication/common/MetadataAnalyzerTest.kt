package com.myapplication.common

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.Verdict

/**
 * [MetadataAnalyzer.generatorMatch] decides "AI, 95%" on the phone with NO
 * server call (AppViewModel / ShareActivity), so it may fire only on a file
 * that confesses its own generation: a parsed PNG text chunk holding
 * generation settings (A1111 'parameters', a ComfyUI graph with a sampler
 * node), or the full IPTC digitalSourceType URL for trainedAlgorithmicMedia.
 *
 * The negatives are real photos the old raw-byte substring scan called AI
 * (audit 2026-09-23 PROV-7, measured with this analyzer on copies of a real
 * JPEG: Artist 'Leonardo Rossi', an Imagenomic Portraiture retouch, a Magic
 * Eraser composite and captions naming generators — 8 of 13 read AI locally).
 * Run: ./gradlew :shared:desktopTest
 */
class MetadataAnalyzerTest {

    // ── real photos that must NOT read as a generator ────────────────────

    @Test
    fun artistNamedLeonardoIsNotAGenerator() {
        val bytes = jpeg(app1Exif(artist = "Leonardo Rossi"))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun imagenomicPortraitureRetouchIsNotAGenerator() {
        // 'Imagen' is a prefix of 'Imagenomic'; the server excludes it (b89d305).
        val bytes = jpeg(app1Xmp(
            "<stEvt:softwareAgent>Imagenomic Portraiture 4</stEvt:softwareAgent>"))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun aiEditCompositeIsNotAGenerationVerdict() {
        // Magic Eraser / Clean Up / Galaxy AI: a real photo with one object
        // removed. 'compositeWithTrainedAlgorithmicMedia' contains
        // 'TrainedAlgorithmicMedia'; it is an edit, the server's call.
        val bytes = jpeg(app1Xmp(
            "Iptc4xmpExt:DigitalSourceType=\"http://cv.iptc.org/newscodes/" +
                "digitalsourcetype/compositeWithTrainedAlgorithmicMedia\""))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun captionNamingGeneratorsIsNotAGenerator() {
        val bytes = jpeg(app1Xmp(
            "<dc:description>Midjourney founder at a Stable Diffusion meetup; " +
                "DALL-E, Adobe Firefly, NovelAI and ComfyUI on the panel. " +
                "Hugging Face diffusers team. Steps: the Spanish Steps. " +
                "Sampler: a cheese sampler. Flux Photography.</dc:description>"))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun fluxInCompressedScanDataIsNotAGenerator() {
        // 4 bytes of entropy-coded data can spell 'Flux' by chance.
        val bytes = jpeg(scan = "..\u0012Flux\u0099..".toByteArray(Charsets.ISO_8859_1))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun bareTrainedAlgorithmicMediaWordIsNotADeclaration() {
        val bytes = jpeg(app1Xmp(
            "<dc:description>checked: no trainedAlgorithmicMedia here</dc:description>"))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun iptcUrlRunningIntoALetterIsNotADeclaration() {
        val bytes = jpeg(app1Xmp(
            "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMediaX"))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun pngParametersChunkWithoutGenerationSettingsIsNotAGenerator() {
        val bytes = png(tEXt("parameters", "exposure=+0.3; gamma=2.2"))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun pngCommentChunkNamingSettingsIsNotAGenerator() {
        val bytes = png(tEXt("Comment", "Steps: 12 stairs. Sampler: Leonardo's Imagen Flux"))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun comfyUpscaleOnlyWorkflowIsNotAGenerator() {
        // A load -> upscale -> save graph run on a REAL photo embeds
        // "class_type" too; only a sampler node is a generation (the server's
        // audit 2026-09-18 fix, _COMFY_SAMPLER_RE).
        val graph = """{"1": {"inputs": {"image": "IMG_4412.jpg"}, "class_type": "LoadImage"},
            "2": {"inputs": {"upscale_model": ["3", 0], "image": ["1", 0]}, "class_type": "ImageUpscaleWithModel"},
            "4": {"inputs": {"images": ["2", 0]}, "class_type": "SaveImage"}}"""
        val bytes = png(tEXt("prompt", graph))
        assertNull(MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    // ── the verdict cache must not replay the old scan's accusations ─────
    // History survives an app update (EncryptedSharedPreferences / file
    // store), and AppViewModel consults it BEFORE the metadata scan, so a
    // PROVENANCE "AI" row the pre-PROV-7 substring scan wrote for a real
    // photo would come back as "Cached result" forever for the same bytes.

    private fun provenanceRow(hash: String) = AnalysisHistoryEntry(
        fileName = "IMG_0001.jpg", fileSize = 1234L, isAI = true,
        confidence = 0.95f, analysisMode = "PROVENANCE", processingTimeMs = 3L,
        verdict = Verdict.AI.name, sha256 = hash)

    @Test
    fun cachedProvenanceRowIsNotReplayed() {
        val bytes = jpeg(app1Exif(artist = "Leonardo Rossi"))
        val hash = sha256Hex(bytes)
        assertNull(cachedVerdict(listOf(provenanceRow(hash)), hash))
    }

    @Test
    fun cachedServerRowIsStillReplayed() {
        val bytes = jpeg(app1Exif(artist = "Leonardo Rossi"))
        val hash = sha256Hex(bytes)
        val server = provenanceRow(hash).copy(
            analysisMode = "SERVER", isAI = false, verdict = Verdict.AUTHENTIC.name)
        // An older PROVENANCE row for the same bytes must not shadow it.
        assertEquals(server, cachedVerdict(listOf(provenanceRow(hash), server), hash))
    }

    // ── files that confess their generation: still decided locally ───────

    @Test
    fun a1111ParametersChunkMatches() {
        val bytes = png(tEXt("parameters",
            "a lighthouse at dusk\nNegative prompt: blurry\n" +
                "Steps: 20, Sampler: Euler a, CFG scale: 7, Seed: 1234, Size: 512x512"))
        assertEquals("sd_webui", MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun a1111ParametersInUncompressedItxtMatches() {
        // A1111 falls back to iTXt when the prompt is not Latin-1.
        val bytes = png(iTXt("parameters",
            "маяк на закате\nSteps: 10, Sampler: Euler, CFG scale: 1.5, Seed: 7"))
        assertEquals("sd_webui", MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun comfyUiGraphWithSamplerNodeMatches() {
        val graph = """{"3": {"inputs": {"seed": 1, "steps": 20}, "class_type": "KSampler"},
            "9": {"inputs": {"images": ["8", 0]}, "class_type": "SaveImage"}}"""
        val bytes = png(tEXt("prompt", graph))
        assertEquals("comfyui", MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun iptcTrainedAlgorithmicMediaUrlInXmpMatches() {
        val bytes = jpeg(app1Xmp(
            "Iptc4xmpExt:DigitalSourceType=\"http://cv.iptc.org/newscodes/" +
                "digitalsourcetype/trainedAlgorithmicMedia\""))
        assertEquals("iptc_ai_declared", MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    @Test
    fun iptcTrainedAlgorithmicMediaUrlInC2paCborMatches() {
        // Bytes as found in a civitai C2PA manifest (data/api_bench/ai/photos/
        // civitai_140992512.jpg): the URL is followed by a CBOR map header 0xA2.
        val cbor = "lSourceTypexFhttp://cv.iptc.org/newscodes/digitalsourcetype/" +
            "trainedAlgorithmicMedia¢factionnc2pa.converted"
        val bytes = jpeg(app11(cbor.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("iptc_ai_declared", MetadataAnalyzer.analyze(bytes).generatorMatch)
    }

    // ── byte builders ────────────────────────────────────────────────────

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = payload.size + 2
        out.write(0xFF); out.write(marker)
        out.write(len ushr 8); out.write(len and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** APP1 Exif with a big-endian TIFF holding one IFD0 entry: Artist. */
    private fun app1Exif(artist: String): ByteArray {
        val value = (artist + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write(v ushr 8); tiff.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        tiff.write("MM".toByteArray()); u16(0x2A); u32(8)
        u16(1)                                   // one IFD entry
        u16(0x013B); u16(2); u32(value.size); u32(8 + 2 + 12 + 4)
        u32(0)                                   // no next IFD
        tiff.write(value)
        return segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray())
    }

    private fun app1Xmp(body: String): ByteArray {
        val packet = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description>" +
            body + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
        return segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) +
            packet.toByteArray(Charsets.UTF_8))
    }

    private fun app11(payload: ByteArray): ByteArray =
        segment(0xEB, "JP".toByteArray() + byteArrayOf(0, 1, 0, 0, 0, 1) + payload)

    private fun jpeg(
        vararg segments: ByteArray,
        scan: ByteArray = ByteArray(64) { (it * 37 + 11).toByte() },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        segments.forEach { out.write(it) }
        out.write(segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)))
        out.write(scan)
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(data.size ushr 24); out.write(data.size ushr 16 and 0xFF)
        out.write(data.size ushr 8 and 0xFF); out.write(data.size and 0xFF)
        out.write(t); out.write(data)
        val crc = CRC32().apply { update(t); update(data) }.value
        out.write((crc ushr 24).toInt() and 0xFF); out.write((crc ushr 16).toInt() and 0xFF)
        out.write((crc ushr 8).toInt() and 0xFF); out.write(crc.toInt() and 0xFF)
        return out.toByteArray()
    }

    private fun tEXt(key: String, value: String): ByteArray =
        chunk("tEXt", (key + "\u0000" + value).toByteArray(Charsets.ISO_8859_1))

    /** Uncompressed iTXt: key\0 flag=0 method=0 lang\0 translated\0 UTF-8 text. */
    private fun iTXt(key: String, value: String): ByteArray =
        chunk("iTXt", (key + "\u0000").toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0) + "\u0000\u0000".toByteArray() + value.toByteArray(Charsets.UTF_8))

    private fun png(vararg textChunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)))
        textChunks.forEach { out.write(it) }
        out.write(chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0x60, 0x60, 0x60, 0, 0, 0, 4, 0, 1)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }
}
