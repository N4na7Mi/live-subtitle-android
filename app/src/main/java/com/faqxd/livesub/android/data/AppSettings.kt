package com.faqxd.livesub.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted application settings.
 *
 * Direct port of `settings.py:AppSettings`. Stored in `SharedPreferences`
 * (`gemini-live-translate.json` equivalent on Android is the prefs file
 * `livebuddy_settings`).
 *
 * Properties:
 *  - apiKey              — Google Gemini API key (stored plaintext, like the
 *                          Windows version; users on rooted devices can read it).
 *  - apiBase             — Override for the API base URL (proxy / regional mirror).
 *  - sourceLanguage      — Source language hint, or "auto".
 *  - targetLanguage      — ISO-639-1 code, e.g. "zh", "es", "ja".
 *  - audioSource         — "mic" or "system" (loopback via MediaProjection).
 *  - audioChunkMs        — PCM send chunk duration. Lower means lower latency.
 *  - audioSampleRate     — PCM sample rate sent to Gemini.
 *  - fontSize            — Caption font size in sp.
 *  - bgOpacity           — 0..1 background alpha for the overlay card.
 *  - captionOpacity      — 0..1 caption text alpha.
 *  - subtitleMaxLines    — Max visual lines in the floating subtitle.
 *  - overlayWidthDp      — Floating subtitle window width.
 *  - overlayHeightDp     — Floating subtitle window height.
 *  - echoTargetLanguage  — Whether to play back the translated audio.
 *  - playbackVolume      — 0..1 playback volume.
 *  - systemPrompt        — Optional custom instructions for the model.
 *  - showOriginal        — Whether to display the source-language transcript.
 */
data class AppSettings(
    var apiKey: String = "",
    var apiBase: String = DEFAULT_API_BASE,
    var proxyEnabled: Boolean = false,
    var proxyType: String = "HTTP",
    var proxyHost: String = "",
    var proxyPort: Int = 7890,
    var sourceLanguage: String = "auto",
    var targetLanguage: String = "zh-CN",
    var audioSource: String = "mic",
    var audioChunkMs: Int = DEFAULT_AUDIO_CHUNK_MS,
    var audioSampleRate: Int = DEFAULT_AUDIO_SAMPLE_RATE,
    var fontSize: Int = 16,
    var bgOpacity: Float = 0.6f,
    var captionOpacity: Float = 1.0f,
    var subtitleMaxLines: Int = DEFAULT_SUBTITLE_MAX_LINES,
    var overlayWidthDp: Int = DEFAULT_OVERLAY_WIDTH_DP,
    var overlayHeightDp: Int = DEFAULT_OVERLAY_HEIGHT_DP,
    var echoTargetLanguage: Boolean = false,
    var playbackVolume: Float = 0.8f,
    var systemPrompt: String = "",
    var showOriginal: Boolean = false,
) {
    companion object {
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        const val DEFAULT_AUDIO_CHUNK_MS = 200
        const val DEFAULT_AUDIO_SAMPLE_RATE = 16000
        const val DEFAULT_SUBTITLE_MAX_LINES = 2
        const val DEFAULT_OVERLAY_WIDTH_DP = 360
        const val DEFAULT_OVERLAY_HEIGHT_DP = 190
        val AUDIO_CHUNK_MS_OPTIONS = intArrayOf(100, 200, 300, 500)
        val AUDIO_SAMPLE_RATE_OPTIONS = intArrayOf(16000, 24000, 48000)
        val SUBTITLE_LINE_OPTIONS = intArrayOf(1, 2, 3, 4)
        private const val PREFS_NAME = "livebuddy_settings"

        fun normalizeAudioChunkMs(value: Int): Int = when {
            value <= 150 -> 100
            value <= 250 -> 200
            value <= 400 -> 300
            else -> 500
        }

        fun normalizeAudioSampleRate(value: Int): Int {
            var best = DEFAULT_AUDIO_SAMPLE_RATE
            var bestDistance = Int.MAX_VALUE
            for (option in AUDIO_SAMPLE_RATE_OPTIONS) {
                val distance = kotlin.math.abs(option - value)
                if (distance < bestDistance) {
                    best = option
                    bestDistance = distance
                }
            }
            return best
        }

        fun normalizeSubtitleMaxLines(value: Int): Int =
            value.coerceIn(SUBTITLE_LINE_OPTIONS.first(), SUBTITLE_LINE_OPTIONS.last())

        fun normalizeAudioSource(value: String): String =
            if (value.equals("system", ignoreCase = true)) "system" else "mic"

        fun normalizeFontSize(value: Int): Int = value.coerceIn(14, 60)

        fun normalizeOverlayWidth(value: Int): Int = value.coerceIn(260, 720)

        fun normalizeOverlayHeight(value: Int): Int = value.coerceIn(96, 520)

        fun load(context: Context): AppSettings {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AppSettings(
                apiKey = prefs.getString("api_key", "") ?: "",
                apiBase = prefs.getString("api_base", DEFAULT_API_BASE) ?: DEFAULT_API_BASE,
                proxyEnabled = prefs.getBoolean("proxy_enabled", false),
                proxyType = prefs.getString("proxy_type", "HTTP") ?: "HTTP",
                proxyHost = prefs.getString("proxy_host", "") ?: "",
                proxyPort = prefs.getInt("proxy_port", 7890),
                sourceLanguage = Languages.normalizeInputCode(
                    prefs.getString("source_language", "auto") ?: "auto"
                ),
                targetLanguage = Languages.normalizeCode(prefs.getString("target_language", "zh-CN") ?: "zh-CN"),
                audioSource = normalizeAudioSource(prefs.getString("audio_source", "mic") ?: "mic"),
                audioChunkMs = normalizeAudioChunkMs(
                    prefs.getInt("audio_chunk_ms", DEFAULT_AUDIO_CHUNK_MS)
                ),
                audioSampleRate = normalizeAudioSampleRate(
                    prefs.getInt("audio_sample_rate", DEFAULT_AUDIO_SAMPLE_RATE)
                ),
                fontSize = normalizeFontSize(prefs.getInt("font_size", 16)),
                bgOpacity = prefs.getFloat("bg_opacity", 0.6f),
                captionOpacity = prefs.getFloat("caption_opacity", 1.0f).coerceIn(0.2f, 1.0f),
                subtitleMaxLines = normalizeSubtitleMaxLines(
                    prefs.getInt("subtitle_max_lines", DEFAULT_SUBTITLE_MAX_LINES)
                ),
                overlayWidthDp = normalizeOverlayWidth(
                    prefs.getInt("overlay_width_dp", DEFAULT_OVERLAY_WIDTH_DP)
                ),
                overlayHeightDp = normalizeOverlayHeight(
                    prefs.getInt("overlay_height_dp", DEFAULT_OVERLAY_HEIGHT_DP)
                ),
                echoTargetLanguage = prefs.getBoolean("echo_target", false),
                playbackVolume = prefs.getFloat("playback_volume", 0.8f),
                systemPrompt = prefs.getString("system_prompt", "") ?: "",
                showOriginal = prefs.getBoolean("show_original", false),
            )
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString("api_key", apiKey)
            putString("api_base", apiBase)
            putBoolean("proxy_enabled", proxyEnabled)
            putString("proxy_type", proxyType)
            putString("proxy_host", proxyHost)
            putInt("proxy_port", proxyPort)
            putString("source_language", Languages.normalizeInputCode(sourceLanguage))
            putString("target_language", targetLanguage)
            putString("audio_source", normalizeAudioSource(audioSource))
            putInt("audio_chunk_ms", normalizeAudioChunkMs(audioChunkMs))
            putInt("audio_sample_rate", normalizeAudioSampleRate(audioSampleRate))
            putInt("font_size", normalizeFontSize(fontSize))
            putFloat("bg_opacity", bgOpacity.coerceIn(0f, 1f))
            putFloat("caption_opacity", captionOpacity.coerceIn(0.2f, 1f))
            putInt("subtitle_max_lines", normalizeSubtitleMaxLines(subtitleMaxLines))
            putInt("overlay_width_dp", normalizeOverlayWidth(overlayWidthDp))
            putInt("overlay_height_dp", normalizeOverlayHeight(overlayHeightDp))
            putBoolean("echo_target", echoTargetLanguage)
            putFloat("playback_volume", playbackVolume)
            putString("system_prompt", systemPrompt)
            putBoolean("show_original", showOriginal)
        }
    }
}
