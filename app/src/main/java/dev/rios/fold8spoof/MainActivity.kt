package dev.rios.fold8spoof

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Setup + status + event log viewer.
 * Dark One UI-style cards, no external dependencies. English UI.
 */
class MainActivity : Activity() {
    private lateinit var statusDot: View
    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressView: TextView
    private lateinit var actionButton: Button
    private lateinit var langDesc: TextView
    private lateinit var logView: TextView
    private val downloading = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val serverOk = AtomicBoolean(false)
    private val autoTries = AtomicInteger(0)
    private val autoHandler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            if (serverOk.get() || autoTries.incrementAndGet() > 45) return
            refreshStatus()
            refreshLogs()
            autoHandler.postDelayed(this, 4000)
        }
    }

    private val bg = 0xFF000000.toInt()
    private val card = 0xFF1E1E1E.toInt()
    private val accent = 0xFF0381FE.toInt()
    private val green = 0xFF4CAF50.toInt()
    private val gray = 0xFF757575.toInt()
    private val red = 0xFFF44336.toInt()
    private val amber = 0xFFFFC107.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(4), 0, dp(6))
        }
    }

    private fun bodyText(text: String, size: Float = 13.5f): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFFCCCCCC.toInt())
            setLineSpacing(0f, 1.25f)
        }
    }

    private fun cardBox(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
    }

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, LlmServerService::class.java))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "✨ Local NPU Summaries"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(bodyText("Galaxy AI-style summaries, 100% on-device (Gemma 3 1B, NPU).", 13f))
        root.addView(spacer(14))

        // ---- STATUS card (auto-refreshes until the engine is up) ----
        val statusCard = cardBox()
        statusCard.addView(sectionTitle("Status"))
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusDot = View(this).apply {
            background = rounded(gray, 20)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(10)
            }
        }
        statusRow.addView(statusDot)
        statusView = bodyText("checking…")
        statusRow.addView(statusView)
        statusCard.addView(statusRow)
        modelView = bodyText("")
        statusCard.addView(modelView)
        root.addView(statusCard)
        root.addView(spacer(12))

        // ---- MODEL card ----
        val modelCard = cardBox()
        modelCard.addView(sectionTitle("Model"))
        modelCard.addView(bodyText(
            "Detected SoC: ${ModelManager.socTag()}\n" +
                "The app downloads the right file by itself (~600–700 MB, use Wi-Fi)."))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        modelCard.addView(progressBar)
        progressView = bodyText("")
        modelCard.addView(progressView)
        actionButton = Button(this).apply {
            text = "Download model"
            background = rounded(accent, 12)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { onAction() }
        }
        modelCard.addView(actionButton)
        root.addView(modelCard)
        root.addView(spacer(12))

        // ---- LANGUAGE card ----
        val langCard = cardBox()
        langCard.addView(sectionTitle("Summary language"))
        val options = SummaryLang.OPTIONS
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.second },
            )
        }
        langDesc = bodyText("")
        val currentMode = SummaryLang.getMode(this)
        spinner.setSelection(options.indexOfFirst { it.first == currentMode }.coerceAtLeast(0))
        langDesc.text = langExplanation(options.firstOrNull { it.first == currentMode }?.first
            ?: SummaryLang.DEVICE)
        var spinnerArmed = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!spinnerArmed) {
                    spinnerArmed = true
                    return
                }
                SummaryLang.setMode(this@MainActivity, options[pos].first)
                langDesc.text = langExplanation(options[pos].first)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        langCard.addView(spinner)
        langCard.addView(spacer(6))
        langCard.addView(langDesc)
        root.addView(langCard)
        root.addView(spacer(12))

        // ---- HOW IT WORKS card ----
        val howCard = cardBox()
        howCard.addView(sectionTitle("How it works"))
        howCard.addView(bodyText(
            "1. Enable the module in LSPosed (scope: Android System, System UI, Settings) and reboot.\n" +
                "2. Open this app once and download the model.\n" +
                "3. New messages trigger an NPU summary in ~1–2 s; it shows collapsed on the notification (✨).\n" +
                "4. Each new message resets the summary until the next cycle (~10 s) — stock Samsung behavior."))
        root.addView(howCard)
        root.addView(spacer(12))

        // ---- LOGS card ----
        val logCard = cardBox()
        logCard.addView(sectionTitle("Summary log"))
        logCard.addView(bodyText(
            "✓ summarized · … waiting for engine · ✗ failure reason (e.g. language not detected, text too short).", 12.5f))
        val logBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val logRefresh = Button(this).apply {
            text = "Refresh"
            background = rounded(0xFF2C2C2C.toInt(), 12)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { refreshLogs() }
        }
        val logClear = Button(this).apply {
            text = "Clear"
            background = rounded(0xFF2C2C2C.toInt(), 12)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                SummaryEventReceiver.clearEvents(this@MainActivity)
                refreshLogs()
            }
        }
        logBtnRow.addView(logRefresh)
        logBtnRow.addView(logClear)
        logCard.addView(logBtnRow)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFBDBDBD.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        logCard.addView(logView)
        root.addView(logCard)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
    }

    private fun langExplanation(mode: String): String {
        return when (mode) {
            SummaryLang.AUTO ->
                "Automatic (experimental): answers in the predominant language of the messages. " +
                    "The 1B model sometimes leaks into English on non-English chats (proven PT→EN leak). " +
                    "If you see an English summary on a non-English chat, switch to “Device language”."
            SummaryLang.DEVICE ->
                "Device language (recommended, default): always summarizes in the system language, " +
                    "which matches the chat language for almost everyone."
            else ->
                "Pinned to ${SummaryLang.languageName(mode)}: every summary comes out in this language, " +
                    "regardless of the messages."
        }
    }

    override fun onResume() {
        super.onResume()
        serverOk.set(false)
        autoTries.set(0)
        autoHandler.removeCallbacks(autoRefresh)
        refreshStatus()
        refreshLogs()
        autoHandler.postDelayed(autoRefresh, 4000)
    }

    override fun onPause() {
        super.onPause()
        autoHandler.removeCallbacks(autoRefresh)
    }

    private fun setDot(color: Int) {
        statusDot.background = rounded(color, 20)
    }

    private fun refreshStatus() {
        Thread({
            val model = ModelManager.resolve(this)
            val health = probeHealth()
            runOnUiThread {
                if (health != null && health.startsWith("ok")) {
                    serverOk.set(true)
                    setDot(green)
                    statusView.text = "Engine up ($health)"
                } else if (model != null) {
                    setDot(amber)
                    statusView.text = "Model ready, engine starting… (${health ?: "stopped"})"
                } else {
                    setDot(red)
                    statusView.text = "No model — download below"
                }
                modelView.text = if (model != null) {
                    "Model: ${model.name} (${model.length() / 1048576} MB)"
                } else {
                    val plan = ModelManager.plan().firstOrNull()
                    "Missing: ${plan?.fileName ?: "?"} (~${(plan?.bytes ?: 0) / 1048576} MB)"
                }
                actionButton.text = if (model != null) "Check again" else "Download model"
            }
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

    private fun refreshLogs() {
        Thread({
            val events = SummaryEventReceiver.readEvents(this)
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val text = if (events.isEmpty()) {
                "No events yet.\nRequests and results show up here as messages arrive."
            } else {
                events.takeLast(40).reversed().joinToString("\n") { line ->
                    val p = line.split('|')
                    val time = try {
                        fmt.format(Date(p[0].toLong()))
                    } catch (_: Exception) {
                        "??:??:??"
                    }
                    val kind = p.getOrNull(1) ?: "?"
                    val key = SummaryEventReceiver.shortKey(p.getOrNull(2) ?: "-")
                    val status = p.getOrNull(3) ?: "-"
                    val len = p.getOrNull(4) ?: "-"
                    val detail = p.getOrNull(5) ?: ""
                    if (kind == "requested") {
                        val l = if (len != "-") " ${len}ch" else ""
                        "$time … $key requested$l" + (if (detail.isNotEmpty()) "\n    “$detail”" else "")
                    } else {
                        val icon = when {
                            status == "SUCCESS" -> "✓"
                            status.startsWith("ERROR") -> "✗"
                            else -> "•"
                        }
                        "$time $icon $key $status"
                    }
                }
            }
            runOnUiThread { logView.text = text }
        }, "setup-logs").apply { isDaemon = true; start() }
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
                    actionButton.text = "Check again"
                    serverOk.set(false)
                    autoTries.set(0)
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
                var done = false
                for (candidate in ModelManager.plan()) {
                    try {
                        ModelManager.download(this, candidate,
                            onProgress = { d, total ->
                                runOnUiThread {
                                    progressBar.progress = ((d * 1000) / total).toInt()
                                    progressView.text =
                                        "${candidate.fileName}: ${d / 1048576} / ${total / 1048576} MB"
                                }
                            },
                            isCancelled = { cancelled.get() })
                        done = true
                        break
                    } catch (t: Throwable) {
                        if (cancelled.get()) throw t
                        android.util.Log.w("Fold8Setup", "download failed for ${candidate.fileName}: $t")
                    }
                }
                if (!done) throw java.io.IOException("all sources failed")
                startForegroundService(Intent(this, LlmServerService::class.java))
                runOnUiThread { progressView.text = "Done. Starting engine…" }
            } catch (t: Throwable) {
                runOnUiThread {
                    progressView.text = if (cancelled.get()) "Cancelled." else "Failed: ${t.message}"
                }
            } finally {
                downloading.set(false)
                runOnUiThread {
                    actionButton.text = "Download model"
                    progressBar.visibility = View.GONE
                    serverOk.set(false)
                    autoTries.set(0)
                    refreshStatus()
                }
            }
        }, "setup-download").apply { start() }
    }
}
