package com.faqxd.livesub.android.gemini

import android.util.Base64
import android.util.Log
import com.faqxd.livesub.android.audio.AudioCapture
import com.faqxd.livesub.android.data.Languages
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
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
    private var apiHostOverride: String = ""
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
        apiHostOverride: String = "",
        proxyEnabled: Boolean = false,
        proxyType: String = "HTTP",
        proxyHost: String = "",
        proxyPort: Int = 7890,
    ) {
        this.apiKey = apiKey.trim()
        this.apiBase = apiBase.ifBlank { DEFAULT_API_BASE }.trim()
        this.apiHostOverride = apiHostOverride.trim()
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
        val summary = connectionSummary(request)
        listener.onStatus("正在连接；$summary")

        ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("已连接；$summary")
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
                val message = "连接失败（$summary）：${t.message ?: "unknown"}"
                listener.onStatus(message)
                listener.onDisconnected(message)
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

        val overrideHost = apiHostOverride.trim()
        val apiHost = apiBaseHost()
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val shouldInspect = apiHost.isNotBlank() && hostname.equals(apiHost, ignoreCase = true)
                val addresses = if (shouldInspect && overrideHost.isNotBlank()) {
                    try {
                        InetAddress.getAllByName(overrideHost).toList()
                    } catch (e: Exception) {
                        throw UnknownHostException(
                            "DNS 直连 IP 无法解析：$overrideHost (${e.safeMessage()})"
                        )
                    }
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
                if (shouldInspect && addresses.any { it.isLoopbackAddress }) {
                    val resolved = addresses.joinToString(",") { it.hostAddress ?: it.toString() }
                    if (overrideHost.isBlank()) {
                        val fallback = PublicDns.lookup(hostname)
                            .filterNot { it.isLoopbackAddress }
                        if (fallback.isNotEmpty()) return fallback
                        throw UnknownHostException(
                            "系统 DNS 将 $hostname 解析到本机地址 $resolved；公共DNS兜底失败。可填写DNS直连IP或关闭手机DNS/VPN拦截"
                        )
                    } else {
                        throw UnknownHostException(
                            "DNS直连IP/Host $overrideHost 解析到本机地址 $resolved；请填写服务器公网IP"
                        )
                    }
                }
                return addresses
            }
        })

        if (proxyEnabled) {
            if (proxyHost.isBlank()) {
                clientConfigError = "代理已启用，但代理地址为空"
                return builder.build()
            }
            if (proxyPort !in 1..65535) {
                clientConfigError = "代理端口必须在 1-65535 之间"
                return builder.build()
            }
            if (isLoopbackHost(proxyHost) && proxyPort in setOf(80, 443)) {
                clientConfigError = "代理地址像是填错了：$proxyHost:$proxyPort；使用反代时请关闭代理"
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
        } else {
            builder.proxy(Proxy.NO_PROXY)
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

    private fun apiBaseHost(): String {
        var base = apiBase.ifBlank { DEFAULT_API_BASE }.trim()
        base = when {
            base.startsWith("wss://") -> "https://" + base.removePrefix("wss://")
            base.startsWith("ws://") -> "http://" + base.removePrefix("ws://")
            base.startsWith("https://") || base.startsWith("http://") -> base
            base.contains("://") -> return ""
            else -> "https://$base"
        }
        return try {
            URI(base).host.orEmpty()
        } catch (_: Exception) {
            ""
        }
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

    private fun connectionSummary(request: Request): String {
        val dns = if (apiHostOverride.isBlank()) {
            "系统DNS/公共DNS兜底"
        } else {
            "直连IP $apiHostOverride"
        }
        val proxy = if (proxyEnabled) {
            "$proxyType $proxyHost:$proxyPort"
        } else {
            "关闭"
        }
        return "目标 ${request.url.host}:${request.url.port}，DNS=$dns，代理=$proxy"
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

private fun isLoopbackHost(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]")
    return h.equals("localhost", ignoreCase = true) ||
        h == "::1" ||
        h == "0:0:0:0:0:0:0:1" ||
        h == "127.0.0.1" ||
        h.startsWith("127.")
}

private object PublicDns {
    private const val DNS_PORT = 53
    private const val TIMEOUT_MS = 1200
    private val SERVERS = listOf("1.1.1.1", "8.8.8.8")
    private val QUERY_TYPES = intArrayOf(1, 28) // A, AAAA

    fun lookup(hostname: String): List<InetAddress> {
        val host = try {
            IDN.toASCII(hostname.trim().trimEnd('.'))
        } catch (_: Exception) {
            return emptyList()
        }
        if (host.isBlank()) return emptyList()

        val out = linkedMapOf<String, InetAddress>()
        for (server in SERVERS) {
            for (type in QUERY_TYPES) {
                query(server, host, type).forEach { address ->
                    val key = address.hostAddress ?: address.toString()
                    out[key] = address
                }
                if (out.isNotEmpty()) return out.values.toList()
            }
        }
        return out.values.toList()
    }

    private fun query(server: String, host: String, qtype: Int): List<InetAddress> {
        val id = (System.nanoTime() and 0xffffL).toInt()
        val request = buildQuery(host, qtype, id)
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val packet = DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName(server),
                    DNS_PORT
                )
                socket.send(packet)

                val buffer = ByteArray(512)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                parseResponse(buffer.copyOf(response.length), id, host)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildQuery(host: String, qtype: Int, id: Int): ByteArray {
        val out = ArrayList<Byte>()
        fun write16(value: Int) {
            out.add(((value ushr 8) and 0xff).toByte())
            out.add((value and 0xff).toByte())
        }

        write16(id)
        write16(0x0100) // standard recursive query
        write16(1)
        write16(0)
        write16(0)
        write16(0)
        host.split('.').forEach { label ->
            val bytes = label.encodeToByteArray()
            if (bytes.isEmpty() || bytes.size > 63) return@forEach
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0)
        write16(qtype)
        write16(1) // IN
        return out.toByteArray()
    }

    private fun parseResponse(data: ByteArray, expectedId: Int, host: String): List<InetAddress> {
        if (data.size < 12) return emptyList()
        val id = read16(data, 0)
        val flags = read16(data, 2)
        if (id != expectedId || (flags and 0x000f) != 0) return emptyList()

        val questionCount = read16(data, 4)
        val answerCount = read16(data, 6)
        var offset = 12
        repeat(questionCount) {
            offset = skipName(data, offset)
            offset += 4
            if (offset > data.size) return emptyList()
        }

        val addresses = mutableListOf<InetAddress>()
        repeat(answerCount) {
            offset = skipName(data, offset)
            if (offset + 10 > data.size) return@repeat
            val type = read16(data, offset)
            val klass = read16(data, offset + 2)
            val len = read16(data, offset + 8)
            offset += 10
            if (offset + len > data.size) return@repeat
            if (klass == 1 && ((type == 1 && len == 4) || (type == 28 && len == 16))) {
                addresses += InetAddress.getByAddress(host, data.copyOfRange(offset, offset + len))
            }
            offset += len
        }
        return addresses
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var offset = start
        var guard = 0
        while (offset < data.size && guard++ < 128) {
            val len = data[offset].toInt() and 0xff
            if (len == 0) return offset + 1
            if ((len and 0xc0) == 0xc0) return offset + 2
            offset += len + 1
        }
        return data.size + 1
    }

    private fun read16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
}
