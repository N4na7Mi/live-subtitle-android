package com.faqxd.livesub.android.data

/**
 * Supported target languages (mirrors `settings.py:LANGUAGES`).
 *
 * `code` is the ISO-639-1 code sent to the Gemini Live API
 * `translationConfig.targetLanguageCode` field.
 */
data class TranslationLanguage(val code: String, val name: String)

object Languages {
    val ALL: List<TranslationLanguage> = listOf(
        TranslationLanguage("en", "英语"),
        TranslationLanguage("es", "西班牙语"),
        TranslationLanguage("fr", "法语"),
        TranslationLanguage("de", "德语"),
        TranslationLanguage("it", "意大利语"),
        TranslationLanguage("ja", "日语"),
        TranslationLanguage("ko", "韩语"),
        TranslationLanguage("zh-CN", "中文（简体）"),
        TranslationLanguage("vi", "越南语"),
        TranslationLanguage("pt", "葡萄牙语"),
        TranslationLanguage("ru", "俄语"),
        TranslationLanguage("hi", "印地语"),
        TranslationLanguage("ar", "阿拉伯语"),
        TranslationLanguage("th", "泰语"),
        TranslationLanguage("id", "印尼语"),
        TranslationLanguage("tr", "土耳其语"),
    )

    val INPUT_ALL: List<TranslationLanguage> =
        listOf(TranslationLanguage("auto", "自动识别")) + ALL

    fun normalizeCode(code: String): String =
        if (code.equals("zh", ignoreCase = true)) "zh-CN" else code

    fun normalizeInputCode(code: String): String {
        val normalized = normalizeCode(code)
        return if (normalized.isBlank()) "auto" else normalized
    }

    fun nameFor(code: String): String =
        ALL.firstOrNull { it.code == normalizeCode(code) }?.name ?: code.uppercase()

    fun inputNameFor(code: String): String =
        INPUT_ALL.firstOrNull { it.code == normalizeInputCode(code) }?.name ?: code.uppercase()
}
