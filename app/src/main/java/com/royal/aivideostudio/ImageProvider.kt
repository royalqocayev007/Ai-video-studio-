package com.royal.aivideostudio

import java.net.URLEncoder

/**
 * Orijinal koddakı Pollinations.ai şəkil URL-i generasiyasının qarşılığı.
 *
 * `styleModifier` ayrıca parametr kimi verilir (Scene-in öz
 * visual_prompt-una qarışdırılmır) ki, istifadəçi üslubu dəyişəndə
 * ssenarini yenidən yaratmağa ehtiyac qalmasın — eyni səhnə məzmunu,
 * fərqli üslub sözləri ilə birləşdirilir.
 */
object ImageProvider {
    fun buildUrl(prompt: String, styleModifier: String, width: Int, height: Int, seed: Int): String {
        val basePrompt = prompt.ifBlank { "cinematic shot" }
        val fullPrompt = if (styleModifier.isBlank()) basePrompt else "$basePrompt, $styleModifier"
        val encoded = URLEncoder.encode(fullPrompt, "UTF-8")
        return "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height&nologo=true&seed=$seed"
    }
}
