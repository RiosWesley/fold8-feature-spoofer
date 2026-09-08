package dev.rios.fold8spoof

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiteRT-LM backend (Gemma3-1B sm8650, NPU with CPU fallback) on localhost.
 * Same HTTP contract as the previous llama-server backend so the LSPosed
 * hook needs no change: GET /health, POST /v1/chat/completions.
 */
class LlmServerService : Service() {
    companion object {
        const val PORT = 18089
        private const val TAG = "Fold8LlmServer"
        private const val MODEL_FILE = "model.litertlm"
    }

    private val engine = LlmNpuEngine()
    private var server: ServerSocket? = null
    private val httpThreads = Executors.newCachedThreadPool()
    private val initStarted = AtomicBoolean(false)
    private var watchdog: Handler? = null

    private fun note(msg: String) {
        Log.i(TAG, msg)
        try {
            val dir = File(filesDir, "llm")
            dir.mkdirs()
            FileOutputStream(File(dir, "status.log"), true).use {
                it.write((Date().toString() + " " + msg + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureStarted()
        if (watchdog == null) {
            watchdog = Handler(Looper.getMainLooper())
            val check = object : Runnable {
                override fun run() {
                    ensureStarted()
                    watchdog?.postDelayed(this, 60000)
                }
            }
            watchdog?.postDelayed(check, 60000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        engine.close()
        super.onDestroy()
    }

    @Synchronized
    private fun ensureStarted() {
        val dir = File(filesDir, "llm")
        if (!dir.isDirectory) dir.mkdirs()
        val model = File(dir, MODEL_FILE)
        if (!model.exists()) {
            note("model.litertlm missing in $dir, server not started")
            return
        }
        if (server == null) {
            try {
                val s = ServerSocket(PORT, 4, java.net.InetAddress.getByName("127.0.0.1"))
                server = s
                Thread({ acceptLoop(s) }, "litert-http").apply { isDaemon = true; start() }
                note("litert http listening on port $PORT")
            } catch (t: Throwable) {
                note("another server already on port $PORT, staying idle")
                startInitIfNeeded(model)
                return
            }
        }
        startInitIfNeeded(model)
    }

    private fun startInitIfNeeded(model: File) {
        if (initStarted.getAndSet(true)) return
        Thread({
            try {
                val cache = File(cacheDir, "litert")
                cache.mkdirs()
                val t0 = System.currentTimeMillis()
                val ok = engine.init(
                    model.absolutePath,
                    applicationInfo.nativeLibraryDir,
                    cache.absolutePath,
                )
                val dt = (System.currentTimeMillis() - t0) / 1000
                note(if (ok) "litert ready backend=${engine.backendName} init=${dt}s"
                    else "litert init FAILED after ${dt}s")
            } catch (t: Throwable) {
                note("litert init error: $t")
            }
        }, "litert-init").apply { isDaemon = true; start() }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val sock = s.accept()
                httpThreads.execute { handle(sock) }
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 300000
                val input = s.getInputStream()
                val headerEnd = readHeaders(input) ?: return
                val (method, path, headers) = headerEnd
                var body = ByteArray(0)
                val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLen > 0) {
                    body = ByteArray(contentLen)
                    var off = 0
                    while (off < contentLen) {
                        val n = input.read(body, off, contentLen - off)
                        if (n < 0) break
                        off += n
                    }
                }
                when {
                    method == "GET" && path.startsWith("/health") -> {
                        if (engine.ready) {
                            reply(s, 200, JSONObject()
                                .put("status", "ok")
                                .put("backend", engine.backendName).toString())
                        } else {
                            reply(s, 503, "{\"status\":\"loading\"}")
                        }
                    }
                    method == "POST" && path.startsWith("/v1/chat/completions") -> {
                        handleChat(s, body)
                    }
                    else -> reply(s, 404, "{\"error\":\"not found\"}")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleChat(s: Socket, body: ByteArray) {
        try {
            val req = JSONObject(body.toString(Charsets.UTF_8))
            val messages = req.optJSONArray("messages")
            var conversation: String? = null
            if (messages != null) {
                for (i in messages.length() - 1 downTo 0) {
                    val m = messages.optJSONObject(i) ?: continue
                    if (m.optString("role") == "user") {
                        conversation = m.optString("content", null)
                        break
                    }
                }
            }
            val maxTokens = req.optInt("max_tokens", 120).coerceIn(32, 512)
            if (conversation.isNullOrEmpty()) {
                reply(s, 400, "{\"error\":\"no user message\"}")
                return
            }
            if (!engine.ready) {
                reply(s, 503, "{\"error\":\"engine not ready\"}")
                return
            }
            val t0 = System.currentTimeMillis()
            val out = engine.generate(conversation, maxTokens)
            if (out.isNullOrEmpty()) {
                reply(s, 500, "{\"error\":\"empty generation\"}")
                return
            }
            val dt = (System.currentTimeMillis() - t0) / 1000.0
            note("litert served backend=${engine.backendName} len=${out.length} t=${dt}s")
            val resp = JSONObject()
                .put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", out))))
            reply(s, 200, resp.toString())
        } catch (t: Throwable) {
            try { reply(s, 500, "{\"error\":\"${t.message}\"}") } catch (_: Exception) {}
        }
    }

    private data class ParsedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private fun readHeaders(input: java.io.InputStream): ParsedRequest? {
        val bos = ByteArrayOutputStream()
        val tail = ByteArray(4)
        var n = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            bos.write(b)
            tail[n % 4] = b.toByte()
            n++
            if (n >= 4 && tail[(n - 4) % 4] == 13.toByte() && tail[(n - 3) % 4] == 10.toByte()
                && tail[(n - 2) % 4] == 13.toByte() && tail[(n - 1) % 4] == 10.toByte()
            ) break
            if (bos.size() > 65536) return null
        }
        val head = bos.toString("ISO-8859-1")
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        for (l in lines.drop(1)) {
            val idx = l.indexOf(':')
            if (idx > 0) headers[l.substring(0, idx).trim().lowercase()] =
                l.substring(idx + 1).trim()
        }
        return ParsedRequest(parts[0], parts[1], headers)
    }

    private fun reply(s: Socket, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Error $code"
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
