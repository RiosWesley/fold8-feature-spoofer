package dev.rios.fold8spoof

import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Pinned per-SoC model catalog (AI Edge Gallery allowlist, 20260310 / 20260217). */
data class ModelCandidate(
    val fileName: String,
    val url: String,
    val bytes: Long,
)

object ModelManager {
    const val DIR = "llm"
    const val LEGACY_FILE = "model.litertlm"

    private const val NPU_BASE = "https://dl.google.com/google-ai-edge-gallery/android/gemma3-1b-npu/20260310/"
    private const val CPU_URL =
        "https://dl.google.com/google-ai-edge-gallery/android/gemma3-1b-it/20260217/gemma3-1b-it-int4.litertlm"
    private const val CPU_BYTES = 584417280L
    const val CPU_FILE = "model-cpu.litertlm"

    fun socTag(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            try { Build.SOC_MODEL ?: "" } catch (_: Throwable) { "" }
        } else ""
        return (soc + " " + Build.BOARD + " " + Build.HARDWARE).lowercase()
    }

    /** NPU file for this SoC, or null when unknown (CPU fallback only). */
    fun npuCandidate(): ModelCandidate? {
        val t = socTag()
        return when {
            "sm8550" in t || "kalama" in t ->
                ModelCandidate("model-npu.litertlm", NPU_BASE + "Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm", 690143232L)
            "sm8650" in t || "pineapple" in t ->
                ModelCandidate("model-npu.litertlm", NPU_BASE + "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm", 690094080L)
            "sm8750" in t || "sun" in t ->
                ModelCandidate("model-npu.litertlm", NPU_BASE + "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm", 689291264L)
            else -> null
        }
    }

    fun cpuCandidate() = ModelCandidate(CPU_FILE, CPU_URL, CPU_BYTES)

    /** Ordered download plan: NPU (if known SoC) then CPU fallback. */
    fun plan(): List<ModelCandidate> =
        listOfNotNull(npuCandidate()) + cpuCandidate()

    fun llmDir(ctx: android.content.Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** Existing valid model file, preferring NPU. Migrates legacy name when it matches. */
    fun resolve(ctx: android.content.Context): File? {
        val dir = llmDir(ctx)
        for (c in plan()) {
            val f = File(dir, c.fileName)
            if (f.exists() && f.length() == c.bytes) return f
        }
        val legacy = File(dir, LEGACY_FILE)
        if (legacy.exists()) {
            val npu = npuCandidate()
            if (npu != null && legacy.length() == npu.bytes) {
                if (legacy.renameTo(File(dir, npu.fileName))) return File(dir, npu.fileName)
                return legacy
            }
            val cpu = cpuCandidate()
            if (legacy.length() == cpu.bytes) {
                if (legacy.renameTo(File(dir, cpu.fileName))) return File(dir, cpu.fileName)
                return legacy
            }
            // Unknown size: still usable (older revision) — prefer it over downloading.
            if (legacy.length() > 100_000_000L) return legacy
        }
        return null
    }

    fun missingPlan(ctx: android.content.Context): List<ModelCandidate> {
        val dir = llmDir(ctx)
        if (resolve(ctx) != null) return emptyList()
        return plan()
    }

    /**
     * Downloads [candidate] to llm dir (resume via .part + Range).
     * Throws on error / cancellation. Verifies exact size at the end.
     */
    fun download(
        ctx: android.content.Context,
        candidate: ModelCandidate,
        onProgress: (done: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        val dir = llmDir(ctx)
        val dest = File(dir, candidate.fileName)
        val part = File(dir, candidate.fileName + ".part")
        var done = if (part.exists()) part.length() else 0L
        // Restart if part is bigger than expected (stale).
        if (done >= candidate.bytes) { part.delete(); done = 0L }
        onProgress(done, candidate.bytes)

        var url = candidate.url
        repeat(5) { // manual redirect following (keeps Range header)
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Fold8Spoofer/1.9")
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
                instanceFollowRedirects = false
            }
            val code = c.responseCode
            if (code in 301..308) {
                url = c.getHeaderField("Location") ?: throw java.io.IOException("redirect without Location")
                c.disconnect()
                return@repeat
            }
            if (code != 200 && code != 206) {
                c.disconnect()
                throw java.io.IOException("http $code for $url")
            }
            BufferedInputStream(c.inputStream).use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(done)
                    val buf = ByteArray(65536)
                    var lastReport = System.currentTimeMillis()
                    while (true) {
                        if (isCancelled()) throw java.io.IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 500) {
                            lastReport = now
                            onProgress(done, candidate.bytes)
                        }
                    }
                }
            }
            c.disconnect()
            onProgress(done, candidate.bytes)
            if (part.length() != candidate.bytes) {
                throw java.io.IOException("size mismatch: got ${part.length()}, want ${candidate.bytes}")
            }
            if (!part.renameTo(dest)) throw java.io.IOException("rename failed")
            try { dest.setReadable(true, false) } catch (_: Throwable) {}
            return dest
        }
        throw java.io.IOException("too many redirects")
    }
}
