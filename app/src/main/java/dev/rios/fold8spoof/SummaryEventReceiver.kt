package dev.rios.fold8spoof

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Receives summary lifecycle events mirrored from the system_server hook
 * (explicit intra-app broadcast, no permissions needed) and appends them
 * to a bounded local log for the in-app viewer.
 *
 * Line format: ts|kind|key|status|len
 *   kind = requested | result
 */
class SummaryEventReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "dev.rios.fold8spoof.SUMMARY_EVENT"
        const val FILE = "events.log"
        private const val MAX_LINES = 300
        private val lock = Any()

        fun readEvents(ctx: Context): List<String> {
            return try {
                val f = File(ctx.filesDir, "llm/$FILE")
                if (!f.exists()) return emptyList()
                f.readLines().filter { it.count { c -> c == '|' } >= 4 }.takeLast(MAX_LINES)
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun clearEvents(ctx: Context) {
            try {
                File(ctx.filesDir, "llm/$FILE").delete()
            } catch (_: Exception) {
            }
        }

        fun shortKey(key: String): String {
            if (key.length <= 18) return key
            // 0|pkg|id|tag|uid -> pkg:tag
            val parts = key.split('|')
            val pkg = parts.getOrNull(1)?.substringAfterLast('.') ?: "?"
            val tag = parts.getOrNull(3)
            return if (tag.isNullOrEmpty() || tag == "null") "$pkg:…" else "$pkg:$tag".take(20)
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        try {
            val dir = File(ctx.filesDir, "llm")
            dir.mkdirs()
            val line = listOf(
                System.currentTimeMillis().toString(),
                intent.getStringExtra("kind") ?: "?",
                intent.getStringExtra("key") ?: "-",
                intent.getStringExtra("status") ?: "-",
                intent.getIntExtra("len", -1).toString(),
            ).joinToString("|")
            synchronized(lock) {
                val f = File(dir, FILE)
                f.appendText(line + "\n")
                val lines = f.readLines()
                if (lines.size > MAX_LINES) {
                    f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
                }
            }
        } catch (_: Exception) {
        }
    }
}
