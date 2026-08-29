package com.royal.aivideostudio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Orijinal Python koddakı edge-tts əvəzinə Android-in daxili
 * Mətndən-nitqə (TextToSpeech) mühərrikini istifadə edir.
 *
 * QEYD: edge-tts-in Azərbaycan neural səsi (az-AZ-BanuNeural) ilə eyni
 * keyfiyyəti vermir — nəticə telefonda quraşdırılmış TTS mühərrikindən
 * (adətən Google) asılıdır. Əgər cihazda Azərbaycan dili üçün səs
 * quraşdırılmayıbsa, istifadəçi Tənzimləmələr > Dil və Giriş > Mətndən
 * nitqə bölməsindən əlavə etməlidir.
 */
class TtsHelper(context: Context, private val onInit: (Boolean) -> Unit) {

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            onInit(status == TextToSpeech.SUCCESS)
        }
    }

    fun localeFor(language: String): Locale = when (language) {
        "Azərbaycan" -> Locale("az", "AZ")
        "English" -> Locale.US
        "Türkçe" -> Locale("tr", "TR")
        "Русский" -> Locale("ru", "RU")
        else -> Locale.US
    }

    fun isLanguageSupported(locale: Locale): Boolean {
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    fun synthesizeToFile(text: String, locale: Locale, outFile: File, onDone: (Boolean) -> Unit) {
        val engine = tts
        if (engine == null) {
            onDone(false)
            return
        }
        engine.language = locale
        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDone(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDone(false)
            }
        })
        val result = engine.synthesizeToFile(text, null, outFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            onDone(false)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
