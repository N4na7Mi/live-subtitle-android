package com.faqxd.livesub.android.gemini

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Port of `gemini_client.py:GeminiClient`.
 *
 * Wraps a single Gemini Live WebSocket session on a background OkHttp
 * dispatcher thread. The caller (LiveTranslateService) feeds PCM16 audio
 * chunks via [sendAudio]; transcript / audio / status callbacks arrive on
 * the supplied listener (already on OkHttp's worker thread — callers should
 * hop to the main thread before touching UI).
 */
class GeminiClient(
    private val listener: Listener,
) {

    interface Listener {
        fun onInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onAudioChunk(pcm16: ByteArray)
        fun onStatus(status: String)
        fun onConnected()
        fun onDisconnected(reason: String)
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false

    private var apiKey: String = ""
    private var apiBase: String = DEFAULT_API_BASE
    private var targetLang: String = "es"
    private var systemPrompt: String = ""
    private var echo: Boolean = true
    private var proxyEnabled: Boolean = false
    private var proxyType: String = "HTTP"
    private var proxyHost: String = ""
    private var proxyPort: Int = 7890
    private var client: OkHttpClient = buildClient()

    fun configure(
        apiKey: String,
        targetLang: String,
        systemPrompt: String,
        echoTargetLanguage: Boolean,
        apiBase: String = DEFAULT_API_BASE,
        proxyEnabled: Boolean = false,
        proxyType: String = "HTTP",
        proxyHost: String = "",
        proxyPort: Int = 7890,
    ) {
        this.apiKey = apiKey.trim()
        this.apiBase = apiBase.ifBlank { DEFAULT_API_BASE }.trim()
        this.targetLang = targetLang
        this.systemPrompt = systemPrompt
        this.echo = echoTargetLanguage
        this.proxyEnabled = proxyEnabled
        this.proxyType = proxyType
        this.proxyHost = proxyHost.trim()
        this.proxyPort = proxyPort
        this.client = buildClient()
    }

    /** Start a new session. No-op if already running. */
    fun start() {
        if (running) return
        running = true

        val request = Request.Builder().url(buildWsUrl()).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("Gemini socket opened")
                if (!sendSetup(webSocket)) {
                    running = false
                    webSocket.close(1000, "setup failed")
                    return
                }
                listener.onStatus("Gemini session ready")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Gemini Live uses text frames only; binary is unexpected.
                handleText(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                running = false
                ws = null
                listener.onStatus("Gemini error: ${t.message ?: "unknown"}")
                listener.onDisconnected("Error: ${t.message ?: "unknown"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                running = false
                ws = null
                listener.onDisconnected("Closed: $reason")
            }
        })
    }

    /** Stop the current session. */
    fun stop() {
        if (!running) return
        running = false
        ws?.close(1000, "client stop")
        ws = null
    }

    /** Thread-safe: enqueue a PCM16 audio chunk for sending. */
    fun sendAudio(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        val socket = ws ?: return
        if (!running) return
        val b64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }.toString()
        socket.send(msg)
    }

    // ---------- internals ----------

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)

        if (proxyEnabled && proxyHost.isNotBlank() && proxyPort in 1..65535) {
            val type = if (proxyType.equals("SOCKS", ignoreCase = true)) {
                Proxy.Type.SOCKS
            } else {
                Proxy.Type.HTTP
            }
            builder.proxy(Proxy(type, InetSocketAddress(proxyHost, proxyPort)))
        }

        return builder.build()
    }

    private fun buildWsUrl(): String {
        var base = apiBase.ifBlank { DEFAULT_API_BASE }.trimEnd('/')
        base = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://")  -> "ws://"  + base.removePrefix("http://")
            else -> base
        }
        return "$base$GEMINI_WS_PATH?key=$apiKey"
    }

    private fun sendSetup(socket: WebSocket): Boolean {
        val setup = JSONObject().apply {
            put("model", GEMINI_MODEL)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("translationConfig", JSONObject().apply {
                    put("targetLanguageCode", targetLang)
                    put("echoTargetLanguage", echo)
                })
            })
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("contextWindowCompression", JSONObject().apply {
                put("triggerTokens", "0")
                put("slidingWindow", JSONObject().apply { put("targetTokens", "0") })
            })
        }
        val instruction = systemPrompt.trim()
        if (instruction.isNotEmpty()) {
            setup.put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().put("text", instruction)) })
            })
        }
        val envelope = JSONObject().put("setup", setup).toString()
        return socket.send(envelope)
    }

    private fun handleText(raw: String) {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            listener.onStatus("Parse failed: ${e.message}")
            return
        }

        // Error
        root.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown")
            listener.onStatus("Gemini error: $msg")
            listener.onDisconnected(msg)
            running = false
            ws?.close(1000, "server error")
            ws = null
            return
        }

        // setupComplete — bare ack
        if (root.has("setupComplete")) return

        val content = root.optJSONObject("serverContent") ?: return

        content.optJSONObject("inputTranscription")?.let { tr ->
            val t = tr.optString("text", "")
            if (t.isNotEmpty()) listener.onInputTranscript(t)
        }

        content.optJSONObject("outputTranscription")?.let { tr ->
            val t = tr.optString("text", "")
            if (t.isNotEmpty()) listener.onOutputTranscript(t)
        }

        content.optJSONObject("modelTurn")?.let { turn ->
            val parts = turn.optJSONArray("parts") ?: return@let
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("inlineData")?.let { inline ->
                    val data = inline.optString("data", "")
                    if (data.isNotEmpty()) {
                        try {
                            val audio = Base64.decode(data, Base64.DEFAULT)
                            listener.onAudioChunk(audio)
                        } catch (_: Exception) { /* ignore malformed */ }
                    }
                }
                val text = part.optString("text", "")
                if (text.isNotEmpty()) listener.onOutputTranscript(text)
            }
        }
    }

    companion object {
        private const val TAG = "GeminiClient"
        const val DEFAULT_API_BASE = "https://generativelanguage.googleapis.com"
        private const val GEMINI_WS_PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val GEMINI_MODEL = "models/gemini-3.5-live-translate-preview"
    }
}
