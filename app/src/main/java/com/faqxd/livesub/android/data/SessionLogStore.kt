package com.faqxd.livesub.android.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionLogStore {
    private const val PREFS_NAME = "livebuddy_session_log"
    private const val KEY_TEXT = "text"
    private const val KEY_LAST_INPUT = "last_input"
    private const val KEY_LAST_OUTPUT = "last_output"
    private const val MAX_CHARS = 30000

    fun load(context: Context): String =
        prefs(context).getString(KEY_TEXT, "") ?: ""

    fun save(context: Context, text: String) {
        prefs(context).edit {
            putString(KEY_TEXT, text.takeLast(MAX_CHARS))
            remove(KEY_LAST_INPUT)
            remove(KEY_LAST_OUTPUT)
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Live subtitle session log", text))
    }

    fun startSession(context: Context) {
        val prefs = prefs(context)
        val current = prefs.getString(KEY_TEXT, "") ?: ""
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val separator = "---- 会话 $stamp ----"
        val next = if (current.isBlank()) {
            separator
        } else {
            current.trimEnd() + "\n\n" + separator
        }
        prefs.edit {
            putString(KEY_TEXT, next.takeLast(MAX_CHARS))
            remove(KEY_LAST_INPUT)
            remove(KEY_LAST_OUTPUT)
        }
    }

    fun appendInput(context: Context, text: String) {
        appendTranscript(context, "原文", text, KEY_LAST_INPUT)
    }

    fun appendOutput(context: Context, text: String) {
        appendTranscript(context, "译文", text, KEY_LAST_OUTPUT)
    }

    private fun appendTranscript(context: Context, label: String, text: String, lastKey: String) {
        val normalized = normalize(text)
        if (normalized.isBlank()) return

        val prefs = prefs(context)
        val last = prefs.getString(lastKey, "") ?: ""
        if (normalized == last || last.startsWith(normalized)) return

        val current = prefs.getString(KEY_TEXT, "") ?: ""
        val updated = if (last.isNotBlank() && normalized.startsWith(last)) {
            val oldLine = "$label：$last"
            val newLine = "$label：$normalized"
            val index = current.lastIndexOf(oldLine)
            if (index >= 0) {
                current.replaceRange(index, index + oldLine.length, newLine)
            } else {
                appendLine(current, newLine)
            }
        } else {
            appendLine(current, "$label：$normalized")
        }

        prefs.edit {
            putString(KEY_TEXT, updated.takeLast(MAX_CHARS))
            putString(lastKey, normalized)
        }
    }

    private fun appendLine(current: String, line: String): String =
        if (current.isBlank()) line else current.trimEnd() + "\n" + line

    private fun normalize(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
