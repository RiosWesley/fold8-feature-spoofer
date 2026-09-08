package dev.rios.fold8spoof

import android.content.Context
import java.util.Locale

/**
 * Summary language: "auto" (same language as the messages), "device"
 * (phone locale) or an explicit ISO-639 code. Solved entirely in the
 * service process — no hook change needed.
 */
object SummaryLang {
    private const val PREF = "fold8spoof"
    private const val KEY = "summary_lang"

    const val AUTO = "auto"
    const val DEVICE = "device"

    /** ISO -> English name for prompt building. */
    private val NAMES = mapOf(
        "en" to "English", "pt" to "Portuguese", "es" to "Spanish",
        "fr" to "French", "de" to "German", "it" to "Italian",
        "nl" to "Dutch", "ru" to "Russian", "ja" to "Japanese",
        "ko" to "Korean", "zh" to "Chinese", "hi" to "Hindi",
        "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish",
    )

    val OPTIONS: List<Pair<String, String>> =
        listOf(AUTO to "Automatic (message language)", DEVICE to "Device language") +
            NAMES.entries.sortedBy { it.value }.map { it.key to it.value }

    fun getMode(ctx: Context): String =
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO
        } catch (_: Throwable) {
            AUTO
        }

    fun setMode(ctx: Context, mode: String) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
        } catch (_: Throwable) {
        }
    }

    fun languageName(iso: String): String {
        val base = iso.lowercase().substringBefore('-').substringBefore('_')
        return NAMES[base] ?: try {
            Locale(base).getDisplayLanguage(Locale.ENGLISH).ifEmpty { base }
        } catch (_: Throwable) {
            base
        }
    }

    fun systemPrompt(ctx: Context): String {
        val head = "Summarize the messages below in at most three short sentences, " +
            "focusing on the key points and any action items. " +
            "Respond only with the summary. "
        return head + when (val mode = getMode(ctx)) {
            AUTO -> "Respond in the predominant language of the messages."
            DEVICE -> {
                val iso = try {
                    ctx.resources.configuration.locales.get(0)?.language ?: "en"
                } catch (_: Throwable) {
                    "en"
                }
                "Respond in ${languageName(iso)}."
            }
            else -> "Respond in ${languageName(mode)}."
        }
    }
}
