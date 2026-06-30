package com.faqxd.livesub.android

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
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
    private lateinit var langSpinner: Spinner
    private lateinit var sourceSpinner: Spinner
    private lateinit var volumeSlider: SeekBar
    private lateinit var echoCheck: CheckBox
    private lateinit var fontSlider: SeekBar
    private lateinit var opacitySlider: SeekBar
    private lateinit var showOriginalCheck: CheckBox
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
        langSpinner = findViewById(R.id.langSpinner)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        volumeSlider = findViewById(R.id.volumeSlider)
        echoCheck = findViewById(R.id.echoCheck)
        fontSlider = findViewById(R.id.fontSlider)
        opacitySlider = findViewById(R.id.opacitySlider)
        showOriginalCheck = findViewById(R.id.showOriginalCheck)
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
            applyFromUi()
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

        volumeSlider.progress = (settings.playbackVolume * 100).toInt()
        echoCheck.isChecked = settings.echoTargetLanguage
        // Range 14..60, so shift by 14
        fontSlider.progress = (settings.fontSize - 14).coerceIn(0, 46)
        opacitySlider.progress = (settings.bgOpacity * 100).toInt()
        showOriginalCheck.isChecked = settings.showOriginal
        promptEdit.setText(settings.systemPrompt)
    }

    private fun applyFromUi() {
        settings.apiKey = apiKeyEdit.text.toString().trim()
        settings.apiBase = apiBaseEdit.text.toString().trim().ifBlank { AppSettings.DEFAULT_API_BASE }
        settings.proxyEnabled = proxyEnabledCheck.isChecked
        settings.proxyType = if (proxyTypeSpinner.selectedItemPosition == 1) "SOCKS" else "HTTP"
        settings.proxyHost = proxyHostEdit.text.toString().trim()
        settings.proxyPort = proxyPortEdit.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 7890
        val langIdx = langSpinner.selectedItemPosition
        if (langIdx in Languages.ALL.indices) {
            settings.targetLanguage = Languages.ALL[langIdx].code
        }
        settings.audioSource = if (sourceSpinner.selectedItemPosition == 1) "system" else "mic"
        settings.playbackVolume = volumeSlider.progress / 100f
        settings.echoTargetLanguage = echoCheck.isChecked
        settings.fontSize = (fontSlider.progress + 14).coerceIn(14, 60)
        settings.bgOpacity = opacitySlider.progress / 100f
        settings.showOriginal = showOriginalCheck.isChecked
        settings.systemPrompt = promptEdit.text.toString().trim()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
