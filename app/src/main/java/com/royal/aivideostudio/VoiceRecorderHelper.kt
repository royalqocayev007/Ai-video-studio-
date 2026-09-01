package com.royal.aivideostudio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * İstifadəçinin öz səsini mikrofonla qeyd etmək üçün (TTS/ElevenLabs-a
 * alternativ). Android 12 (S) və yuxarısında MediaRecorder-in
 * konstruktoru dəyişib (Context tələb edir), ona görə versiyaya görə
 * ayrı-ayrı yaradırıq.
 */
class VoiceRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var isRecording = false
        private set

    fun startRecording(outFile: File): Boolean {
        return try {
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
            isRecording = true
            true
        } catch (e: Exception) {
            isRecording = false
            false
        }
    }

    fun stopRecording() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Çox qısa qeydiyyat və s. səbəbdən xəta ola bilər — problem deyil
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
            // buraxılıb
        }
        recorder = null
        isRecording = false
    }
}
