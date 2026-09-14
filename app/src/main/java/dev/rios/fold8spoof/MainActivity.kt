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
                // Group by notification key: latest requested + latest result per conversation.
                val byKey = LinkedHashMap<String, Array<String?>>()
                val order = ArrayList<String>()
                for (line in events) {
                    val p = line.split('|')
                    if (p.size < 6) continue
                    val kind = p[1]
                    if (kind != "requested" && kind != "result") continue
                    val key = p[2]
                    val slot = byKey.getOrPut(key) {
                        order.add(key)
                        arrayOfNulls(2)
                    }
                    if (kind == "requested") slot[0] = line else slot[1] = line
                }
                if (order.isEmpty()) {
                    "No summary events yet (only highlight changes so far)."
                } else {
                    order.takeLast(15).reversed().joinToString("\n\n") { key ->
                        val slot = byKey[key] ?: arrayOfNulls(2)
                        val title = conversationTitle(key, slot[0])
                        val reqLine = slot[0]?.let { formatRequested(it, fmt) } ?: "- never requested"
                        val resLine = slot[1]?.let { formatResult(it, fmt) } ?: "... waiting for result"
                        "$title\n$reqLine\n$resLine"
                    }
                }
            }
            runOnUiThread { logView.text = text }
        }, "setup-logs").apply { isDaemon = true; start() }
    }
    private fun conversationTitle(key: String, reqLine: String?): String {
        val parts = key.split('|')
        val pkg = parts.getOrNull(1)?.substringAfterLast('.') ?: "?"
        val detail = reqLine?.split('|')?.getOrNull(5) ?: ""
        val senders = detail.substringBefore(" | ").substringAfter("·", "")
            .split(',').filter { it.isNotEmpty() }.take(2).joinToString(", ")
        val who = if (senders.isNotEmpty()) senders else key.takeLast(8)
        return "▸ $pkg · $who"
    }

    private fun formatRequested(line: String, fmt: SimpleDateFormat): String {
        val p = line.split('|')
        val time = timeOf(p, fmt)
        val detail = p.getOrNull(5) ?: ""
        val meta = detail.substringBefore(" | ")
        val excerpt = detail.substringAfter(" | ", "")
        val len = p.getOrNull(4) ?: "-"
        return time + " \u2026 requested " + (if (len != "-") len + "ch " else "") + "(" + meta + ")" + (if (excerpt.isNotEmpty()) "\n    \u201c" + excerpt + "\u201d" else "")
    }

    private fun formatResult(line: String, fmt: SimpleDateFormat): String {
        val p = line.split('|')
        val time = timeOf(p, fmt)
        val status = p.getOrNull(3) ?: "-"
        val icon = when {
            status == "SUCCESS" -> "\u2713"
            status.startsWith("ERROR") -> "\u2717"
            else -> "\u2022"
        }
        return time + " " + icon + " " + status
    }

    private fun timeOf(p: List<String>, fmt: SimpleDateFormat): String {
        return try {
            fmt.format(Date(p[0].toLong()))
        } catch (_: Exception) {
            "??:??:??"
        }
    }
}
