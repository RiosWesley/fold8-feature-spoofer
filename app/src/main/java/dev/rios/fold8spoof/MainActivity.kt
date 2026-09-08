package dev.rios.fold8spoof

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First-run setup: downloads the Gemma3-1B model matching this SoC
 * (NPU file when known, CPU fallback otherwise), picks the summary
 * language and starts the local server. Everything else is automatic.
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressView: TextView
    private lateinit var actionButton: Button
    private val downloading = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, LlmServerService::class.java))
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun addTitle(text: String) = TextView(this).apply {
            this.text = text
            textSize = 18f
            layout.addView(this)
        }
        fun addText(text: String) = TextView(this).apply {
            this.text = text
            layout.addView(this)
        }
        addTitle("Fold8 Spoofer + local summaries")
        addText("SoC: ${ModelManager.socTag()}")
        addText("Summary language:")
        val options = SummaryLang.OPTIONS
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.second },
            )
        }
        val currentMode = SummaryLang.getMode(this)
        spinner.setSelection(options.indexOfFirst { it.first == currentMode }.coerceAtLeast(0))
        var spinnerArmed = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // First callback comes from setSelection, not the user.
                if (!spinnerArmed) {
                    spinnerArmed = true
                    return
                }
                SummaryLang.setMode(this@MainActivity, options[pos].first)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        layout.addView(spinner)
        statusView = addText("…")
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            layout.addView(this)
        }
        progressView = addText("")
        actionButton = Button(this).apply {
            text = "Check & download model"
            setOnClickListener { onAction() }
            layout.addView(this)
        }
        addText(
            "\nSetup:\n" +
                "1. Enable this module in LSPosed (scope: System Framework, System UI, Settings) and reboot.\n" +
                "2. Open this app once and download the model (Wi-Fi recommended, ~600-700 MB).\n" +
                "3. Keep battery above 30% and turn the screen off — summaries appear on collapsed notifications.",
        )
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        Thread({
            val model = ModelManager.resolve(this)
            val health = probeHealth()
            val text = buildString {
                append("Model: ")
                append(model?.let { it.name + " (" + it.length() / 1048576 + " MB)" } ?: "not downloaded")
                append("\nServer 127.0.0.1:")
                append(LlmServerService.PORT)
                append(": ")
                append(health ?: "stopped")
            }
            runOnUiThread { statusView.text = text }
        }, "setup-status").apply { isDaemon = true; start() }
    }

    private fun probeHealth(): String? {
        var s: Socket? = null
        return try {
            s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", LlmServerService.PORT), 2000)
            s.soTimeout = 3000
            s.getOutputStream().write("GET /health HTTP/1.0\r\n\r\n".toByteArray())
            val buf = ByteArray(512)
            val n = s.getInputStream().read(buf)
            if (n <= 0) return "unreachable"
            val body = String(buf, 0, n, Charsets.UTF_8)
            if ("\"ok\"" in body) {
                val backend = Regex("\"backend\"\\s*:\\s*\"(.*?)\"").find(body)?.groupValues?.get(1)
                if (backend != null) "ok ($backend)" else "ok"
            } else "starting…"
        } catch (_: Exception) {
            null
        } finally {
            try { s?.close() } catch (_: Exception) {}
        }
    }

    private fun onAction() {
        if (downloading.get()) {
            cancelled.set(true)
            return
        }
        Thread({
            val existing = ModelManager.resolve(this)
            if (existing != null) {
                startForegroundService(Intent(this, LlmServerService::class.java))
                runOnUiThread {
                    actionButton.text = "Check & download model"
                    refreshStatus()
                }
                return@Thread
            }
            if (!downloading.compareAndSet(false, true)) return@Thread
            cancelled.set(false)
            runOnUiThread {
                actionButton.text = "Cancel download"
                progressBar.visibility = View.VISIBLE
            }
            try {
                for (candidate in ModelManager.plan()) {
                    // Skip NPU file when this device has no NPU plan entry mismatch is impossible;
                    // try each in order, first success wins.
                    try {
                        ModelManager.download(this, candidate,
                            onProgress = { done, total ->
                                runOnUiThread {
                                    progressBar.progress = ((done * 1000) / total).toInt()
                                    progressView.text =
                                        "${candidate.fileName}: ${done / 1048576} / ${total / 1048576} MB"
                                }
                            },
                            isCancelled = { cancelled.get() })
                        break
                    } catch (t: Throwable) {
                        if (cancelled.get()) throw t
                        // Try next candidate (e.g. NPU file unusable -> CPU fallback).
                        android.util.Log.w("Fold8Setup", "download failed for ${candidate.fileName}: $t")
                    }
                }
                // Cleanup partial leftovers of the winning file only; keep it simple.
                startForegroundService(Intent(this, LlmServerService::class.java))
                runOnUiThread { progressView.text = "Done. Server starting…" }
            } catch (t: Throwable) {
                runOnUiThread {
                    progressView.text = if (cancelled.get()) "Cancelled." else "Failed: ${t.message}"
                }
            } finally {
                downloading.set(false)
                runOnUiThread {
                    actionButton.text = "Check & download model"
                    progressBar.visibility = View.GONE
                    refreshStatus()
                }
            }
        }, "setup-download").apply { start() }
    }
}
