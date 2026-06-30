package com.faqxd.livesub.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port of `audio.py:AudioCapture`.
 *
 * Captures microphone audio at 16 kHz mono PCM16 — the exact format Gemini
 * Live expects — and forwards fixed-size chunks to [onChunk].
 *
 * Android 10+ can also capture **system / loopback audio** via
 * [MediaProjection.createVirtualDisplay] + [AudioPlaybackCaptureConfiguration],
 * but that requires the Activity result from the foreground service. To keep
 * this class focused, the system-audio path is exposed through
 * [fromMediaProjection] and the AudioRecord is configured by the caller.
 *
 * Practical note: on Android, capturing at the device's native sample rate
 * is more reliable than forcing 16 kHz on the AudioRecord itself. So we
 * capture at the native rate and resample to 16 kHz before forwarding.
 */
class AudioCapture(
    private val onChunk: (ByteArray) -> Unit,
    chunkMs: Int = DEFAULT_CHUNK_MS,
) {
    private val downsampler = PCM16Downsampler(targetRate = GEMINI_INPUT_RATE)
    private val chunkSize = chunkSizeForMs(chunkMs)
    private val chunker = PCM16Chunker(chunkSize = chunkSize, onChunk = onChunk)

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var running = false
    private var sampleRate = 0
    private var channels = 1

    /**
     * Start microphone capture at 16 kHz mono.
     *
     * Requires `RECORD_AUDIO` permission. The calling service MUST hold a
     * foreground service of type `microphone` (Android 14+).
     */
    @SuppressLint("MissingPermission")
    fun startMicrophone() {
        if (running) return

        // Prefer 16 kHz mono natively so no resampling is required.
        val rate = GEMINI_INPUT_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(chunkSize * 2)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw RuntimeException("AudioRecord failed to initialize (state=${ar.state})")
        }
        record = ar
        sampleRate = rate
        channels = 1
        running = true
        ar.startRecording()
        captureThread = Thread({ micLoop(ar, minBuf) }, "AudioCapture-mic").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Start system-audio (loopback) capture using a pre-configured
     * [AudioRecord] obtained from a MediaProjection's
     * `AudioPlaybackCaptureConfiguration`.
     *
     * The caller is responsible for building the AudioRecord (because it
     * needs the MediaProjection token, which we don't have here).
     */
    fun startSystemAudio(record: AudioRecord, srcRate: Int, srcChannels: Int) {
        if (running) return
        this.record = record
        this.sampleRate = srcRate
        this.channels = srcChannels
        running = true
        record.startRecording()
        val bufSize = AudioRecord.getMinBufferSize(
            srcRate,
            if (srcChannels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(chunkSize * 2)
        captureThread = Thread({ systemLoop(record, bufSize) }, "AudioCapture-sys").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        captureThread?.join(500)
        captureThread = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        chunker.reset()
    }

    // ---------- internals ----------

    private fun micLoop(ar: AudioRecord, bufSize: Int) {
        // 16 kHz mono PCM16: each frame is 2 bytes. We read in chunks and
        // forward directly — no resampling
        // needed because we requested the target rate.
        val buf = ByteArray(bufSize)
        while (running) {
            val read = ar.read(buf, 0, buf.size)
            when {
                read <= 0 -> {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        Log.w(TAG, "AudioRecord.read returned $read, stopping")
                        break
                    }
                    // transient; keep going
                }
                else -> chunker.append(buf.copyOf(read))
            }
        }
    }

    private fun systemLoop(ar: AudioRecord, bufSize: Int) {
        // System audio often arrives at 48 kHz stereo. Resample + downmix
        // before chunking.
        val buf = ByteArray(bufSize)
        while (running) {
            val read = ar.read(buf, 0, buf.size)
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.w(TAG, "system AudioRecord.read returned $read, stopping")
                    break
                }
                continue
            }
            // PCM16 LE bytes → Float32 array
            val frames = read / 2 / channels
            val floats = FloatArray(frames * channels)
            val sb = ByteBuffer
                .wrap(buf, 0, frames * channels * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            for (i in 0 until frames * channels) {
                floats[i] = sb.get(i) / 32768f
            }
            val pcm16 = downsampler.convert(floats, sampleRate, channels)
            chunker.append(pcm16)
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val GEMINI_INPUT_RATE = 16000
        const val DEFAULT_CHUNK_MS = 200

        fun chunkSizeForMs(ms: Int): Int {
            val safeMs = ms.coerceIn(80, 600)
            val bytes = GEMINI_INPUT_RATE * 2 * safeMs / 1000
            return if (bytes % 2 == 0) bytes else bytes + 1
        }
    }
}
