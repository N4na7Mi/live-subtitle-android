package com.faqxd.livesub.android.gemini

import android.util.Base64
import android.util.Log
import com.faqxd.livesub.android.audio.AudioCapture
import com.faqxd.livesub.android.data.Languages
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
    @Volatile private var ready = false

    private var apiKey: String = ""
    private var apiBase: String = DEFAULT_API_BASE
    private var sourceLang: String = "auto"
    private var targetLang: String = "zh-CN"
    private var inputAudioRate: Int = AudioCapture.DEFAULT_INPUT_RATE
    private var systemPrompt: String = ""
    private var echo: Boolean = false
    private var proxyEnabled: Boolean = false
    private var proxyType: String = "HTTP"
    private var proxyHost: String = ""
    private var proxyPort: Int = 7890
    private var clientConfigError: String? = null
    private var client: OkHttpClient = buildClient()

    fun configure(
        apiKey: String,
        sourceLang: String,
        targetLang: String,
        inputAudioRate: Int,
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
        this.sourceLang = Languages.normalizeInputCode(sourceLang)
        this.targetLang = targetLang
        this.inputAudioRate = AudioCapture.normalizeInputRate(inputAudioRate)
        this.systemPrompt = systemPrompt
        this.echo = echoTargetLanguage
        this.proxyEnabled = proxyEnabled
        this.proxyType = proxyType
        this.proxyHost = proxyHost.trim()
        this.proxyPort = proxyPort
        this.client = buildClient()
    }

    fun start(): Boolean {
        if (running) return true
        clientConfigError?.let { error ->
            failBeforeConnect(error)
            return false
        }
        running = true
        ready = false

        val request = try {
            Request.Builder().url(buildWsUrl()).build()
        } catch (e: Exception) {
            failBeforeConnect("连接配置无效：${e.safeMessage()}")
            return false
        }

        ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("已连接，正在初始化 Gemini")
                if (!sendSetup(webSocket)) {
                    running = false
                    ready = false
                    listener.onStatus("Gemini setup 发送失败；点开始重试")
                    listener.onDisconnected("Gemini setup 发送失败")
                    webSocket.close(1000, "setup failed")
                    return
                }
                listener.onStatus("等待 Gemini 初始化")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleText(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                running = false
                ready = false
                ws = null
                listener.onStatus("Gemini error: ${t.message ?: "unknown"}")
                listener.onDisconnected("Error: ${t.message ?: "unknown"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasRunning = running
                running = false
                ready = false
                ws = null
                if (wasRunning) listener.onDisconnected("Closed: $reason")
            }
        })
        } catch (e: Exception) {
            failBeforeConnect("连接启动失败：${e.safeMessage()}")
            return false
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        ready = false
        ws?.close(1000, "client stop")
        ws = null
    }

    fun sendAudio(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        val socket = ws ?: return
        if (!running || !ready) return
        val b64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", "audio/pcm;rate=$inputAudioRate")
                })
            })
        }.toString()
        socket.send(msg)
    }

    private fun buildClient(): OkHttpClient {
        clientConfigError = null
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)

        if (proxyEnabled) {
            if (proxyHost.isBlank()) {
                clientConfigError = "代理已启用，但代理地址为空"
                return builder.build()
            }
            if (proxyPort !in 1..65535) {
                clientConfigError = "代理端口必须在 1-65535 之间"
                return builder.build()
            }
            val type = if (proxyType.equals("SOCKS", ignoreCase = true)) {
                Proxy.Type.SOCKS
            } else {
                Proxy.Type.HTTP
            }
            try {
                builder.proxy(Proxy(type, InetSocketAddress(proxyHost, proxyPort)))
            } catch (e: Exception) {
                clientConfigError = "代理配置无效：${e.safeMessage()}"
            }
        }

        return builder.build()
    }

    private fun buildWsUrl(): String {
        var base = apiBase.ifBlank { DEFAULT_API_BASE }.trimEnd('/')
        base = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("wss://") || base.startsWith("ws://") -> base
            base.contains("://") -> base
            base.isNotBlank() -> "wss://$base"
            else -> base
        }
        val hostAndPath = base.substringAfter("://", base)
        val endpoint = if (hostAndPath.contains("/")) base else "$base$GEMINI_WS_PATH"
        if (Regex("[?&]key=").containsMatchIn(endpoint)) return endpoint
        val separator = if (endpoint.contains("?")) "&" else "?"
        return "$endpoint${separator}key=$apiKey"
    }

    private fun sendSetup(socket: WebSocket): Boolean {
        val setup = JSONObject().apply {
            put("model", GEMINI_MODEL)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("translationConfig", JSONObject().apply {
                    put("targetLanguageCode", Languages.normalizeCode(targetLang))
                    put("echoTargetLanguage", true)
                })
            })
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("contextWindowCompression", JSONObject().apply {
                put("triggerTokens", "0")
                put("slidingWindow", JSONObject().apply { put("targetTokens", "0") })
            })
        }

        val instruction = buildSystemInstruction()
        if (instruction.isNotEmpty()) {
            setup.put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().put("text", instruction)) })
            })
        }

        return socket.send(JSONObject().put("setup", setup).toString())
    }

    private fun buildSystemInstruction(): String {
        val prompt = systemPrompt.trim()
        if (sourceLang.equals("auto", ignoreCase = true)) return prompt

        val hint = "Source audio language hint: ${Languages.promptNameFor(sourceLang)}."
        return listOf(hint, prompt)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun failBeforeConnect(message: String) {
        running = false
        ready = false
        ws = null
        listener.onStatus("$message；改设置后点开始重试")
        listener.onDisconnected(message)
    }

    private fun handleText(raw: String) {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            listener.onStatus("Parse failed: ${e.message}")
            return
        }

        root.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown")
            listener.onStatus("Gemini error: $msg")
            listener.onDisconnected(msg)
            running = false
            ready = false
            ws?.close(1000, "server error")
            ws = null
            return
        }

        if (root.has("setupComplete")) {
            ready = true
            listener.onStatus("Gemini session ready")
            listener.onConnected()
            return
        }

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
                            listener.onAudioChunk(Base64.decode(data, Base64.DEFAULT))
                        } catch (_: Exception) {
                        }
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

private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName
