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
import org.json.JSONArray
import org.json.JSONObject

/**
 * First-run wizard (welcome -> download -> language -> verify) plus the
 * main screen (status + summary log). Dark One UI-style, English, no deps.
 */
class MainActivity : Activity() {
    private val bg = 0xFF000000.toInt()
    private val card = 0xFF1E1E1E.toInt()
    private val accent = 0xFF0381FE.toInt()
    private val green = 0xFF4CAF50.toInt()
    private val gray = 0xFF757575.toInt()
    private val red = 0xFFF44336.toInt()
    private val amber = 0xFFFFC107.toInt()

    private var wizardStep = 0
    private lateinit var wizardBox: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var stepLabel: TextView

    private lateinit var statusDot: View
    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressView: TextView
    private lateinit var langDesc: TextView
    private lateinit var logView: TextView
    private lateinit var verifyView: TextView
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }
    }

    private fun title(text: String, size: Float = 22f): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
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

    private fun body(text: String, size: Float = 13.5f): TextView {
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

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            background = rounded(if (primary) accent else 0xFF2C2C2C.toInt(), 12)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun navRow(onBack: (() -> Unit)?, onNext: (() -> Unit)?, nextLabel: String): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (onBack != null) {
            row.addView(button("Back", false, onBack).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        if (onNext != null) {
            row.addView(button(nextLabel, true, onNext).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    // ---------------- WIZARD ----------------

    private fun showWizard() {
        wizardBox.visibility = View.VISIBLE
        mainBox.visibility = View.GONE
        renderWizardStep()
    }

    private fun showMain() {
        wizardBox.visibility = View.GONE
        mainBox.visibility = View.VISIBLE
        refreshStatus()
        refreshLogs()
    }

    private fun renderWizardStep() {
        wizardBox.removeAllViews()
        stepLabel.text = "Step ${wizardStep + 1} of 4"
        when (wizardStep) {
            0 -> wizardWelcome()
            1 -> wizardDownload()
            2 -> wizardLanguage()
            else -> wizardVerify()
        }
    }

    private fun wizardWelcome() {
        val c = cardBox()
        c.addView(sectionTitle("Local AI summaries"))
        c.addView(body(
            "This module brings Galaxy AI-style notification summaries to your phone, " +
                "generated 100% on-device by Gemma 3 1B running on the NPU.\n\n" +
                "You need:\n" +
                "• Root + LSPosed, module enabled for Android System, System UI and Settings, then reboot\n" +
                "• ~700 MB free for the model (Wi-Fi recommended)\n" +
                "• Snapdragon with NPU (8 Gen 3 fully tested; others fall back to CPU)"))
        wizardBox.addView(c)
        wizardBox.addView(spacer(12))
        wizardBox.addView(navRow(null, { wizardStep = 1; renderWizardStep() }, "Next"))
    }

    private fun wizardDownload() {
        val c = cardBox()
        c.addView(sectionTitle("Download the model"))
        c.addView(body("Detected SoC: ${ModelManager.socTag()}\nThe right file is picked automatically."))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        c.addView(progressBar)
        progressView = body("")
        c.addView(progressView)
        val model = ModelManager.resolve(this)
        if (model != null) {
            c.addView(body("Already downloaded: ${model.name} (${model.length() / 1048576} MB)."))
        } else {
            c.addView(button("Download (~${(ModelManager.plan().firstOrNull()?.bytes ?: 0) / 1048576} MB)", true) {
                startDownload { renderWizardStep() }
            })
        }
        wizardBox.addView(c)
        wizardBox.addView(spacer(12))
        wizardBox.addView(navRow({ wizardStep = 0; renderWizardStep() }, {
            wizardStep = 2; renderWizardStep()
        }, if (model != null) "Next" else "Skip for now"))
    }

    private fun languageSpinner(selected: String, onPick: (String) -> Unit): Spinner {
        val options = SummaryLang.OPTIONS
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.second },
            )
        }
        spinner.setSelection(options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
        var armed = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!armed) {
                    armed = true
                    return
                }
                SummaryLang.setMode(this@MainActivity, options[pos].first)
                onPick(options[pos].first)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return spinner
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

    private fun wizardLanguage() {
        val c = cardBox()
        c.addView(sectionTitle("Summary language"))
        langDesc = body("")
        val mode = SummaryLang.getMode(this)
        c.addView(languageSpinner(mode) { langDesc.text = langExplanation(it) })
        c.addView(spacer(6))
        langDesc.text = langExplanation(mode)
        c.addView(langDesc)
        wizardBox.addView(c)
        wizardBox.addView(spacer(12))
        wizardBox.addView(navRow({ wizardStep = 1; renderWizardStep() }, {
            wizardStep = 3; renderWizardStep()
        }, "Next"))
    }

    private fun wizardVerify() {
        val c = cardBox()
        c.addView(sectionTitle("Verify it works"))
        c.addView(body("Runs a sample summary on the NPU engine, exactly like a real notification would."))
        verifyView = body("")
        c.addView(verifyView)
        c.addView(button("Run test summary", true) { runTestSummary() })
        wizardBox.addView(c)
        wizardBox.addView(spacer(12))
        wizardBox.addView(navRow({ wizardStep = 2; renderWizardStep() }, {
            setOnboarded(true)
            showMain()
        }, "Finish"))
    }

    private fun runTestSummary() {
        verifyView.text = "Running…"
        Thread({
            try {
                val sample = "John: who can bring snacks tomorrow? Mary: I will bring chips. " +
                    "Peter: the meeting moved to 3pm. Lisa: please send the report by Friday."
                val t0 = System.currentTimeMillis()
                val out = postTestChat(sample)
                val dt = (System.currentTimeMillis() - t0) / 1000.0
                runOnUiThread {
                    verifyView.text = if (out != null) "✓ ${dt}s:\n$out" else "Engine not ready yet — wait a minute and retry."
                }
            } catch (t: Throwable) {
                runOnUiThread { verifyView.text = "Failed: ${t.message}" }
            }
        }, "test-summary").apply { start() }
    }

    private fun postTestChat(conversation: String): String? {
        var s: Socket? = null
        return try {
            val req = JSONObject()
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "x"))
                    .put(JSONObject().put("role", "user").put("content", conversation)))
                .put("temperature", 0.2)
                .put("max_tokens", 120)
            val body = req.toString().toByteArray(Charsets.UTF_8)
            s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", LlmServerService.PORT), 5000)
            s.soTimeout = 120000
            val head = "POST /v1/chat/completions HTTP/1.0\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
            s.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            s.getOutputStream().write(body)
            val buf = ByteArray(65536)
            val out = StringBuilder()
            var n: Int
            s.soTimeout = 10000
            try {
                while (true) {
                    n = s.getInputStream().read(buf)
                    if (n < 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
            val resp = out.toString()
            val json = resp.substringAfter("{", "")
            if (json.isEmpty()) return null
            JSONObject("{$json").optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        } catch (_: Exception) {
            null
        } finally {
            try { s?.close() } catch (_: Exception) {}
        }
    }

    // ---------------- MAIN ----------------

    private fun buildMain() {
        mainBox.removeAllViews()
        val statusCard = cardBox()
        statusCard.addView(sectionTitle("Status"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusDot = View(this).apply {
            background = rounded(gray, 20)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(10)
            }
        }
        row.addView(statusDot)
        statusView = body("checking…")
        row.addView(statusView)
        statusCard.addView(row)
        modelView = body("")
        statusCard.addView(modelView)
        mainBox.addView(statusCard)
        mainBox.addView(spacer(12))

        val langCard = cardBox()
        langCard.addView(sectionTitle("Summary language"))
        langDesc = body("")
        val mode = SummaryLang.getMode(this)
        langCard.addView(languageSpinner(mode) { langDesc.text = langExplanation(it) })
        langCard.addView(spacer(6))
        langDesc.text = langExplanation(mode)
        langCard.addView(langDesc)
        mainBox.addView(langCard)
        mainBox.addView(spacer(12))

        val logCard = cardBox()
        logCard.addView(sectionTitle("Summary log"))
        logCard.addView(body(
            "✓ summarized · … waiting for engine · ✗ failure reason (e.g. language not detected, text too short).", 12.5f))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(button("Refresh", false) { refreshLogs() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        btnRow.addView(button("Clear", false) {
            SummaryEventReceiver.clearEvents(this@MainActivity)
            refreshLogs()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        logCard.addView(btnRow)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFBDBDBD.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        logCard.addView(logView)
        mainBox.addView(logCard)
    }

    // ---------------- LIFECYCLE ----------------

    private fun isOnboarded(): Boolean {
        return try {
            getSharedPreferences("fold8spoof", MODE_PRIVATE).getBoolean("onboarded", false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun setOnboarded(v: Boolean) {
        try {
            getSharedPreferences("fold8spoof", MODE_PRIVATE).edit().putBoolean("onboarded", v).apply()
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, LlmServerService::class.java))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        root.addView(title("✨ Local NPU Summaries"))
        root.addView(body("Galaxy AI-style summaries, 100% on-device (Gemma 3 1B, NPU).", 13f))
        root.addView(spacer(6))
        stepLabel = body("", 12.5f)
        root.addView(stepLabel)
        root.addView(spacer(8))
        wizardBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(wizardBox)
        root.addView(mainBox)
        buildMain()

        if (isOnboarded() || ModelManager.resolve(this) != null) {
            stepLabel.text = ""
            showMain()
        } else {
            showWizard()
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
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
        if (::statusDot.isInitialized) statusDot.background = rounded(color, 20)
    }

    private fun refreshStatus() {
        if (wizardBox.visibility == View.VISIBLE) return
        Thread({
            val model = ModelManager.resolve(this)
            val health = probeHealth()
            runOnUiThread {
                if (!::statusView.isInitialized) return@runOnUiThread
                if (health != null && health.startsWith("ok")) {
                    serverOk.set(true)
                    setDot(green)
                    statusView.text = "Engine up ($health)"
                } else if (model != null) {
                    setDot(amber)
                    statusView.text = "Model ready, engine starting… (${health ?: "stopped"})"
                } else {
                    setDot(red)
                    statusView.text = "No model — run the setup wizard"
                    if (!isOnboarded()) {
                        wizardStep = 0
                        showWizard()
                    }
                    return@runOnUiThread
                }
                modelView.text = if (model != null) {
                    "Model: ${model.name} (${model.length() / 1048576} MB)"
                } else ""
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
        if (!::logView.isInitialized || mainBox.visibility != View.VISIBLE) return
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

    private fun startDownload(onDone: () -> Unit) {
        if (downloading.get()) {
            cancelled.set(true)
            return
        }
        Thread({
            if (!downloading.compareAndSet(false, true)) return@Thread
            cancelled.set(false)
            runOnUiThread { progressBar.visibility = View.VISIBLE }
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
                    progressBar.visibility = View.GONE
                    onDone()
                }
            }
        }, "setup-download").apply { start() }
    }

    private fun onAction() {
        // Kept for programmatic callers; wizard uses startDownload directly.
        startDownload { renderWizardStep() }
    }
}
