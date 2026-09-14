package dev.rios.fold8spoof

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status + summary/highlight event log viewer.
 * Summaries run on Samsung's own OLM/NPU path; this app only observes.
 */
class MainActivity : Activity() {
    private lateinit var logView: TextView
    private val autoHandler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            refreshLogs()
            autoHandler.postDelayed(this, 5000)
        }
    }

    private val bg = 0xFF000000.toInt()
    private val card = 0xFF1E1E1E.toInt()
    private val accent = 0xFF0381FE.toInt()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "✨ Native Summaries"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(body("Samsung OLM on-device summaries + event log.", 13f))
        root.addView(spacer(14))

        val howCard = cardBox()
        howCard.addView(sectionTitle("Setup"))
        howCard.addView(body(
            "1. Enable the module in LSPosed (scope: Android System, System UI, Settings) and reboot.\n" +
                "2. New messages trigger Samsung summaries on the NPU; they show collapsed (✨).\n" +
                "3. Watch requests, results and highlight changes below."))
        root.addView(howCard)
        root.addView(spacer(12))

        val logCard = cardBox()
        logCard.addView(sectionTitle("Summary log"))
        logCard.addView(body(
            "✓ summarized · … requested · ✗ failure reason · ◆ highlight section members.", 12.5f))
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
        root.addView(logCard)
        root.addView(spacer(12))
        root.addView(button("View source on GitHub", false) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/RiosWesley/fold8-feature-spoofer")))
            } catch (_: Exception) {
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        autoHandler.removeCallbacks(autoRefresh)
        refreshLogs()
        autoHandler.postDelayed(autoRefresh, 5000)
    }

    override fun onPause() {
        super.onPause()
        autoHandler.removeCallbacks(autoRefresh)
    }

    private fun refreshLogs() {
        if (!::logView.isInitialized) return
        Thread({
            val events = SummaryEventReceiver.readEvents(this)
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val text = if (events.isEmpty()) {
                "No events yet.\nRequests, results and highlight changes show up here as messages arrive."
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
                    if (kind == "highlight") {
                        "$time ◆ HL: ${(if (detail.isNotEmpty()) detail else "(none)")}"
                    } else if (kind == "requested") {
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
}
