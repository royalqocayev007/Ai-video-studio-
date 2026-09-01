package com.royal.aivideostudio

import android.media.MediaPlayer

/**
 * Səs fayllarını (TTS, ElevenLabs, ya öz səsimiz) dinləmək üçün sadə
 * "sarğı" (wrapper). Hər dəfə yeni səs çalınanda əvvəlkini dayandırır —
 * eyni anda iki səs üst-üstə düşməsin deyə.
 */
class AudioPlayerHelper {
    private var player: MediaPlayer? = null

    fun play(path: String, onDone: () -> Unit = {}) {
        stop()
        player = MediaPlayer().apply {
            try {
                setDataSource(path)
                setOnCompletionListener {
                    onDone()
                    release()
                }
                setOnErrorListener { _, _, _ ->
                    onDone()
                    true
                }
                prepare()
                start()
            } catch (e: Exception) {
                onDone()
            }
        }
    }

    fun stop() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                // artıq buraxılıbsa, problem deyil
            }
        }
        player = null
    }
}
