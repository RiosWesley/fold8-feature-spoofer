package dev.rios.fold8spoof

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

/**
 * Setup + status + event log viewer.
 * Dark One UI-style cards, no external dependencies.
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

    private val bg = 0xFF000000.toInt()
    private val card = 0xFF1E1E1E.toInt()
    private val accent = 0xFF0381FE.toInt()
    private val green = 0xFF4CAF50.toInt()
    private val gray = 0xFF757575.toInt()
    private val red = 0xFFF44336.toInt()
    private val amber = 0xFFFFC107.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
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

        // Header
        root.addView(TextView(this).apply {
            text = "✨ Resumos locais NPU"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(bodyText("Galaxy AI summaries, 100% on-device (Gemma 3 1B, NPU).", 13f))
        root.addView(spacer(14))

        // ---- STATUS card ----
        val statusCard = cardBox()
        statusCard.addView(sectionTitle("Estado"))
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusDot = View(this).apply {
            background = rounded(gray, 20)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(10)
            }
        }
        statusRow.addView(statusDot)
        statusView = bodyText("verificando…")
        statusRow.addView(statusView)
        statusCard.addView(statusRow)
        modelView = bodyText("")
        statusCard.addView(modelView)
        val refreshBtn = Button(this).apply {
            text = "Atualizar estado"
            background = rounded(0xFF2C2C2C.toInt(), 12)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { refreshStatus() }
        }
        statusCard.addView(refreshBtn)
        root.addView(statusCard)
        root.addView(spacer(12))

        // ---- MODEL card ----
        val modelCard = cardBox()
        modelCard.addView(sectionTitle("Modelo"))
        modelCard.addView(bodyText(
            "SoC detectado: ${ModelManager.socTag()}\n" +
                "O app baixa sozinho o arquivo certo (~600–700 MB, use Wi-Fi)."))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        modelCard.addView(progressBar)
        progressView = bodyText("")
        modelCard.addView(progressView)
        actionButton = Button(this).apply {
            text = "Baixar modelo"
            background = rounded(accent, 12)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { onAction() }
        }
        modelCard.addView(actionButton)
        root.addView(modelCard)
        root.addView(spacer(12))

        // ---- LANGUAGE card ----
        val langCard = cardBox()
        langCard.addView(sectionTitle("Idioma do resumo"))
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
        howCard.addView(sectionTitle("Como funciona"))
        howCard.addView(bodyText(
            "1. Ative o módulo no LSPosed com escopo em Sistema Framework, System UI e Configurações, e reinicie.\n" +
                "2. Abra este app uma vez e baixe o modelo.\n" +
                "3. Novas mensagens disparam o resumo na NPU em ~1–2 s; ele aparece recolhido na notificação (✨).\n" +
                "4. Cada mensagem nova zera o resumo até o próximo ciclo (~10 s) — comportamento original da Samsung."))
        root.addView(howCard)
        root.addView(spacer(12))

        // ---- LOGS card ----
        val logCard = cardBox()
        logCard.addView(sectionTitle("Registro de resumos"))
        logCard.addView(bodyText(
            "✓ resumida · … aguardando motor · ✗ motivo da falha (ex.: idioma não detectado, texto curto).", 12.5f))
        val logBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val logRefresh = Button(this).apply {
            text = "Atualizar"
            background = rounded(0xFF2C2C2C.toInt(), 12)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { refreshLogs() }
        }
        val logClear = Button(this).apply {
            text = "Limpar"
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

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
        setContentView(scroll)
    }

    private fun langExplanation(mode: String): String {
        return when (mode) {
            SummaryLang.AUTO ->
                "Automático (experimental): responde no idioma predominante das mensagens. " +
                    "O modelo 1B às vezes vaza para o inglês em conversas não-inglesas (vazamento PT→EN comprovado). " +
                    "Se ver resumo em inglês numa conversa em português, troque para “Idioma do aparelho”."
            SummaryLang.DEVICE ->
                "Idioma do aparelho (recomendado e padrão): resume sempre no idioma configurado no sistema, " +
                    "que para quase todo mundo é o idioma das conversas."
            else ->
                "Fixo em ${SummaryLang.languageName(mode)}: todo resumo sai neste idioma, " +
                    "independentemente do idioma das mensagens."
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLogs()
    }

    private fun setDot(color: Int) {
        statusDot.background = rounded(color, 20)
    }

    private fun refreshStatus() {
        statusView.text = "verificando…"
        setDot(gray)
        Thread({
            val model = ModelManager.resolve(this)
            val health = probeHealth()
            runOnUiThread {
                if (health != null && health.startsWith("ok")) {
                    setDot(green)
                    statusView.text = "Servidor ativo ($health)"
                } else if (model != null) {
                    setDot(amber)
                    statusView.text = "Modelo pronto, motor iniciando… (${health ?: "parado"})"
                } else {
                    setDot(red)
                    statusView.text = "Sem modelo — baixe abaixo"
                }
                modelView.text = if (model != null) {
                    "Modelo: ${model.name} (${model.length() / 1048576} MB)"
                } else {
                    val plan = ModelManager.plan().firstOrNull()
                    "Falta baixar: ${plan?.fileName ?: "?"} (~${(plan?.bytes ?: 0) / 1048576} MB)"
                }
                actionButton.text = if (model != null) "Verificar novamente" else "Baixar modelo"
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
            if (n <= 0) return "inacessível"
            val body = String(buf, 0, n, Charsets.UTF_8)
            if ("\"ok\"" in body) {
                val backend = Regex("\"backend\"\\s*:\\s*\"(.*?)\"").find(body)?.groupValues?.get(1)
                if (backend != null) "ok ($backend)" else "ok"
            } else "iniciando…"
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
                "Nenhum evento ainda.\nOs pedidos e resultados aparecem aqui quando chegam mensagens."
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
                    val icon = when {
                        kind == "requested" -> "…"
                        status == "SUCCESS" -> "✓"
                        status.startsWith("ERROR") -> "✗"
                        else -> "•"
                    }
                    val extra = if (kind == "requested") {
                        val l = if (len != "-") " ${len}ch" else ""
                        "pedido$l"
                    } else {
                        status
                    }
                    "$time $icon $key $extra"
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
                    actionButton.text = "Verificar novamente"
                    refreshStatus()
                }
                return@Thread
            }
            if (!downloading.compareAndSet(false, true)) return@Thread
            cancelled.set(false)
            runOnUiThread {
                actionButton.text = "Cancelar download"
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
                if (!done) throw java.io.IOException("todas as fontes falharam")
                startForegroundService(Intent(this, LlmServerService::class.java))
                runOnUiThread { progressView.text = "Pronto. Servidor iniciando…" }
            } catch (t: Throwable) {
                runOnUiThread {
                    progressView.text = if (cancelled.get()) "Cancelado." else "Falhou: ${t.message}"
                }
            } finally {
                downloading.set(false)
                runOnUiThread {
                    actionButton.text = "Baixar modelo"
                    progressBar.visibility = View.GONE
                    refreshStatus()
                }
            }
        }, "setup-download").apply { start() }
    }
}
