package com.royal.aivideostudio

import java.net.URLEncoder

/** Orijinal koddakı Pollinations.ai şəkil URL-i generasiyasının qarşılığı. */
object ImageProvider {
    fun buildUrl(prompt: String, width: Int, height: Int, seed: Int): String {
        val safePrompt = prompt.ifBlank { "cinematic shot" }
        val encoded = URLEncoder.encode(safePrompt, "UTF-8")
        return "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height&nologo=true&seed=$seed"
    }
}
