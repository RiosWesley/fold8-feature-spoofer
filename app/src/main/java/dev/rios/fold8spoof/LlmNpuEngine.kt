package dev.rios.fold8spoof

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/** LiteRT-LM inference wrapper: NPU (sm8650) with CPU fallback. Thread-safe, one shot per call. */
final class LlmNpuEngine {
    @Volatile var backendName: String = "none"
        private set
    @Volatile var ready: Boolean = false
        private set

    private var engine: Engine? = null
    private val lock = Any()
    private val inferLock = Any()

    // Content-addressed cache: WhatsApp re-posts the same conversation on
    // every sync (new record -> NONE -> raw flicker). Same text + same
    // prompt returns the previous summary instantly instead of re-running
    // the NPU. TTL bounds staleness (e.g. after a language switch).
    private val summaryCache = object : LinkedHashMap<Int, Pair<Long, String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, String>>?) =
            size > 30
    }
    private val cacheLock = Any()
    private val CACHE_TTL_MS = 10 * 60 * 1000L

    /** Blocking init (call off the main thread). Returns true when usable. */
    fun init(modelPath: String, nativeLibDir: String, cacheDir: String): Boolean {
        synchronized(lock) {
            if (ready) return true
            // Try NPU first (QNN v75 stack bundled in jniLibs), then CPU.
            val attempts = listOf(
                "npu" to Backend.NPU(nativeLibraryDir = nativeLibDir),
                "cpu" to Backend.CPU(),
            )
            for ((name, backend) in attempts) {
                try {
                    val cfg = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        cacheDir = cacheDir,
                    )
                    val e = Engine(cfg)
                    e.initialize()
                    engine = e
                    backendName = name
                    ready = true
                    return true
                } catch (t: Throwable) {
                    android.util.Log.w("Fold8LlmServer", "litert $name backend failed: $t")
                }
            }
            return false
        }
    }

    /** One-shot summary generation. Serialized: one NPU inference at a time. */
    fun generate(conversation: String, maxTokens: Int, systemPrompt: String): String? {
        val e: Engine
        synchronized(lock) { e = engine ?: return null }
        synchronized(inferLock) {
        val cacheKey = 31 * conversation.hashCode() + systemPrompt.hashCode()
        synchronized(cacheLock) {
            val hit = summaryCache[cacheKey]
            if (hit != null && System.currentTimeMillis() - hit.first < CACHE_TTL_MS
                && hit.second.length <= 400
            ) {
                android.util.Log.i("Fold8LlmServer", "litert cache hit len=${hit.second.length}")
                return hit.second
            }
        }
        // Fresh conversation per request: stateless like the llama-server backend.
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.2, seed = 0),
            maxOutputToken = maxTokens.coerceIn(32, 512),
        )
        return try {
            e.createConversation(cfg).use { conv ->
                val msg = conv.sendMessage(conversation)
                val text = msg.contents.contents
                    .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                    .joinToString("") { it.text }
                    .ifEmpty { msg.toString() }
                if (text.isNotEmpty()) {
                    synchronized(cacheLock) {
                        summaryCache[cacheKey] = System.currentTimeMillis() to text
                    }
                }
                text.ifEmpty { null }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Fold8LlmServer", "litert generate failed: $t")
            null
        }
        } // inferLock
    }

    fun close() {
        synchronized(lock) {
            try { engine?.close() } catch (_: Throwable) {}
            engine = null
            ready = false
            backendName = "none"
        }
    }
}
