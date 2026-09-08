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

    /** One-shot summary generation. Returns null on failure. */
    fun generate(conversation: String, maxTokens: Int, systemPrompt: String): String? {
        val e: Engine
        synchronized(lock) { e = engine ?: return null }
        // Fresh conversation per request: stateless like the llama-server backend.
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.2, seed = 0),
            maxOutputToken = maxTokens.coerceIn(32, 512),
        )
        return try {
            e.createConversation(cfg).use { conv ->
                val msg = conv.sendMessage(conversation)
                msg.contents.contents
                    .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                    .joinToString("") { it.text }
                    .ifEmpty { msg.toString() }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Fold8LlmServer", "litert generate failed: $t")
            null
        }
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
