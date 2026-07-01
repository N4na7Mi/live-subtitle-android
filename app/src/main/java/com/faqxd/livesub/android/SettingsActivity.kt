package com.faqxd.livesub.android

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.faqxd.livesub.android.data.AppSettings
import com.faqxd.livesub.android.data.Languages

/**
 * Port of `settings_window.py:SettingsDialog`.
 *
 * MVP: a single scrollable form with the same sections as the Windows version
 * (Connection / Audio / Appearance / Advanced).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    private lateinit var apiKeyEdit: EditText
    private lateinit var showKeyBtn: Button
    private lateinit var apiBaseEdit: EditText
    private lateinit var proxyEnabledCheck: CheckBox
    private lateinit var proxyTypeSpinner: Spinner
    private lateinit var proxyHostEdit: EditText
    private lateinit var proxyPortEdit: EditText
    private lateinit var inputLangSpinner: Spinner
    private lateinit var langSpinner: Spinner
    private lateinit var sourceSpinner: Spinner
    private lateinit var audioChunkSpinner: Spinner
    private lateinit var audioSampleRateSpinner: Spinner
    private lateinit var volumeSlider: SeekBar
    private lateinit var echoCheck: CheckBox
    private lateinit var opacitySlider: SeekBar
    private lateinit var captionOpacitySlider: SeekBar
    private lateinit var subtitleLinesSpinner: Spinner
    private lateinit var promptEdit: EditText
    private lateinit var saveBtn: Button
    private lateinit var cancelBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        settings = AppSettings.load(this)
        bindViews()
        populateViews()
    }

    private fun bindViews() {
        apiKeyEdit = findViewById(R.id.apiKeyEdit)
        showKeyBtn = findViewById(R.id.showKeyBtn)
        apiBaseEdit = findViewById(R.id.apiBaseEdit)
        proxyEnabledCheck = findViewById(R.id.proxyEnabledCheck)
        proxyTypeSpinner = findViewById(R.id.proxyTypeSpinner)
        proxyHostEdit = findViewById(R.id.proxyHostEdit)
        proxyPortEdit = findViewById(R.id.proxyPortEdit)
        inputLangSpinner = findViewById(R.id.inputLangSpinner)
        langSpinner = findViewById(R.id.langSpinner)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        audioChunkSpinner = findViewById(R.id.audioChunkSpinner)
        audioSampleRateSpinner = findViewById(R.id.audioSampleRateSpinner)
        volumeSlider = findViewById(R.id.volumeSlider)
        echoCheck = findViewById(R.id.echoCheck)
        opacitySlider = findViewById(R.id.opacitySlider)
        captionOpacitySlider = findViewById(R.id.captionOpacitySlider)
        subtitleLinesSpinner = findViewById(R.id.subtitleLinesSpinner)
        promptEdit = findViewById(R.id.promptEdit)
        saveBtn = findViewById(R.id.saveBtn)
        cancelBtn = findViewById(R.id.cancelBtn)

        showKeyBtn.setOnClickListener {
            val showing = apiKeyEdit.inputType and InputType.TYPE_MASK_VARIATION !=
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (showing) {
                apiKeyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                showKeyBtn.text = getString(R.string.show_key)
            } else {
                apiKeyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                showKeyBtn.text = getString(R.string.hide_key)
            }
        }

        saveBtn.setOnClickListener {
            if (!applyFromUi()) return@setOnClickListener
            settings.save(this)
            setResult(RESULT_OK)
            finish()
        }
        cancelBtn.setOnClickListener { finish() }
    }

    private fun populateViews() {
        apiKeyEdit.setText(settings.apiKey)
        apiBaseEdit.setText(settings.apiBase)
        if (apiBaseEdit.text.isBlank()) apiBaseEdit.hint = AppSettings.DEFAULT_API_BASE
        proxyEnabledCheck.isChecked = settings.proxyEnabled
        proxyHostEdit.setText(settings.proxyHost)
        proxyPortEdit.setText(settings.proxyPort.takeIf { it > 0 }?.toString().orEmpty())

        proxyTypeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("HTTP", "SOCKS")
        )
        proxyTypeSpinner.setSelection(if (settings.proxyType.equals("SOCKS", ignoreCase = true)) 1 else 0)

        inputLangSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Languages.INPUT_ALL.map { it.name }
        )
        val selectedInputLanguage = Languages.normalizeInputCode(settings.sourceLanguage)
        inputLangSpinner.setSelection(
            Languages.INPUT_ALL.indexOfFirst { it.code == selectedInputLanguage }.coerceAtLeast(0)
        )

        langSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Languages.ALL.map { it.name }
        )
        val selectedLanguage = Languages.normalizeCode(settings.targetLanguage)
        langSpinner.setSelection(Languages.ALL.indexOfFirst { it.code == selectedLanguage }.coerceAtLeast(0))

        sourceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.audio_source_mic), getString(R.string.audio_source_system))
        )
        sourceSpinner.setSelection(if (settings.audioSource == "system") 1 else 0)

        audioChunkSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            AppSettings.AUDIO_CHUNK_MS_OPTIONS.map { chunkLabel(it) }
        )
        val chunkSelection = AppSettings.AUDIO_CHUNK_MS_OPTIONS
            .indexOf(AppSettings.normalizeAudioChunkMs(settings.audioChunkMs))
            .coerceAtLeast(0)
        audioChunkSpinner.setSelection(chunkSelection)

        audioSampleRateSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            AppSettings.AUDIO_SAMPLE_RATE_OPTIONS.map { sampleRateLabel(it) }
        )
        val sampleRateSelection = AppSettings.AUDIO_SAMPLE_RATE_OPTIONS
            .indexOf(AppSettings.normalizeAudioSampleRate(settings.audioSampleRate))
            .coerceAtLeast(0)
        audioSampleRateSpinner.setSelection(sampleRateSelection)

        volumeSlider.progress = (settings.playbackVolume * 100).toInt()
        echoCheck.isChecked = settings.echoTargetLanguage
        opacitySlider.progress = (settings.bgOpacity * 100).toInt()
        captionOpacitySlider.progress = (settings.captionOpacity * 100).toInt()
        subtitleLinesSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            AppSettings.SUBTITLE_LINE_OPTIONS.map { subtitleLineLabel(it) }
        )
        val lineSelection = AppSettings.SUBTITLE_LINE_OPTIONS
            .indexOf(AppSettings.normalizeSubtitleMaxLines(settings.subtitleMaxLines))
            .coerceAtLeast(0)
        subtitleLinesSpinner.setSelection(lineSelection)
        promptEdit.setText(settings.systemPrompt)
    }

    private fun applyFromUi(): Boolean {
        val proxyEnabled = proxyEnabledCheck.isChecked
        val proxyHost = proxyHostEdit.text.toString().trim()
        val proxyPortText = proxyPortEdit.text.toString().trim()
        val proxyPort = proxyPortText.toIntOrNull()
        if (proxyEnabled && proxyHost.isBlank()) {
            val message = getString(R.string.err_proxy_host_required)
            proxyHostEdit.error = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return false
        }
        if (proxyEnabled && (proxyPort == null || proxyPort !in 1..65535)) {
            val message = getString(R.string.err_proxy_port_invalid)
            proxyPortEdit.error = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return false
        }

        settings.apiKey = apiKeyEdit.text.toString().trim()
        settings.apiBase = apiBaseEdit.text.toString().trim().ifBlank { AppSettings.DEFAULT_API_BASE }
        settings.proxyEnabled = proxyEnabled
        settings.proxyType = if (proxyTypeSpinner.selectedItemPosition == 1) "SOCKS" else "HTTP"
        settings.proxyHost = proxyHost
        settings.proxyPort = proxyPort?.coerceIn(1, 65535) ?: 7890
        val inputLangIdx = inputLangSpinner.selectedItemPosition
        if (inputLangIdx in Languages.INPUT_ALL.indices) {
            settings.sourceLanguage = Languages.INPUT_ALL[inputLangIdx].code
        }
        val langIdx = langSpinner.selectedItemPosition
        if (langIdx in Languages.ALL.indices) {
            settings.targetLanguage = Languages.ALL[langIdx].code
        }
        settings.audioSource = AppSettings.normalizeAudioSource(
            if (sourceSpinner.selectedItemPosition == 1) "system" else "mic"
        )
        settings.audioChunkMs = AppSettings.AUDIO_CHUNK_MS_OPTIONS
            .getOrElse(audioChunkSpinner.selectedItemPosition) { AppSettings.DEFAULT_AUDIO_CHUNK_MS }
        settings.audioSampleRate = AppSettings.AUDIO_SAMPLE_RATE_OPTIONS
            .getOrElse(audioSampleRateSpinner.selectedItemPosition) { AppSettings.DEFAULT_AUDIO_SAMPLE_RATE }
        settings.playbackVolume = volumeSlider.progress / 100f
        settings.echoTargetLanguage = echoCheck.isChecked
        settings.bgOpacity = opacitySlider.progress / 100f
        settings.captionOpacity = (captionOpacitySlider.progress / 100f).coerceIn(0.2f, 1f)
        settings.subtitleMaxLines = AppSettings.SUBTITLE_LINE_OPTIONS
            .getOrElse(subtitleLinesSpinner.selectedItemPosition) { AppSettings.DEFAULT_SUBTITLE_MAX_LINES }
        settings.showOriginal = false
        settings.systemPrompt = promptEdit.text.toString().trim()
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun chunkLabel(ms: Int): String = when (ms) {
        100 -> "100 ms（低延迟）"
        200 -> "200 ms（推荐）"
        300 -> "300 ms（更稳）"
        else -> "500 ms（高稳定，高延迟）"
    }

    private fun sampleRateLabel(rate: Int): String = when (rate) {
        16000 -> "16 kHz（推荐）"
        24000 -> "24 kHz"
        else -> "48 kHz（高带宽）"
    }

    private fun subtitleLineLabel(lines: Int): String = when (lines) {
        1 -> "1 行（最干净）"
        2 -> "2 行（推荐）"
        else -> "$lines 行"
    }
}
