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
 *  - targetLanguage      — ISO-639-1 code, e.g. "zh", "es", "ja".
 *  - audioSource         — "mic" or "system" (loopback via MediaProjection).
 *  - fontSize            — Caption font size in sp.
 *  - bgOpacity           — 0..1 background alpha for the overlay card.
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
    var targetLanguage: String = "zh",
    var audioSource: String = "mic",
    var fontSize: Int = 16,
    var bgOpacity: Float = 0.6f,
    var echoTargetLanguage: Boolean = false,
    var playbackVolume: Float = 0.8f,
    var systemPrompt: String = "",
    var showOriginal: Boolean = false,
) {
    companion object {
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        private const val PREFS_NAME = "livebuddy_settings"

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
                targetLanguage = prefs.getString("target_language", "zh") ?: "zh",
                audioSource = prefs.getString("audio_source", "mic") ?: "mic",
                fontSize = prefs.getInt("font_size", 16),
                bgOpacity = prefs.getFloat("bg_opacity", 0.6f),
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
            putString("target_language", targetLanguage)
            putString("audio_source", audioSource)
            putInt("font_size", fontSize)
            putFloat("bg_opacity", bgOpacity)
            putBoolean("echo_target", echoTargetLanguage)
            putFloat("playback_volume", playbackVolume)
            putString("system_prompt", systemPrompt)
            putBoolean("show_original", showOriginal)
        }
    }
}
