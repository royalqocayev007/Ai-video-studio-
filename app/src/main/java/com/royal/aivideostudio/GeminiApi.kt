package com.royal.aivideostudio

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini ilə ssenari generasiyası.
 *
 * Mərhələ 1 dəyişikliyi: artıq sabit "3 səhnə" istəmirik. Modelə
 * ÜMUMI VİDEO UZUNLUĞUNU veririk, o özü neçə səhnə lazım olduğuna
 * qərar verir və hər səhnənin uzunluğunu elə bölüşdürür ki, cəm
 * tam istənilən ədədə bərabər olsun.
 */
object GeminiApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun buildSystemInstruction(totalDurationSeconds: Int): String =
        "You are an AI video scriptwriter. Respond ONLY with valid JSON, no markdown fences, no extra text. " +
            "The video's TOTAL DURATION must be EXACTLY $totalDurationSeconds seconds. " +
            "Decide how many scenes are needed yourself — typically one scene per 5 to 8 seconds of narration — " +
            "and choose each scene's duration so that the sum of every 'duration_sec' value equals EXACTLY " +
            "$totalDurationSeconds. Do not leave gaps and do not overlap. " +
            "Return a JSON object with a 'scenes' list. Each item must have: " +
            "'scene_id' (integer, starting at 1), " +
            "'narration_text' (spoken narration, in the same language as the user's idea), " +
            "'visual_prompt' (in English, describing ONLY the physical scene content — no art-style words, " +
            "style will be added separately), " +
            "'duration_sec' (integer), " +
            "'subtitle_text' (short subtitle version of the narration)."

    /**
     * Modelin riyaziyyatda səhv etmə ehtimalına qarşı (AI-lar bəzən
     * cəmi 1-2 saniyə səhv hesablayır) son yoxlama: bütün
     * duration_sec-ləri toplayıb, fərq varsa son səhnəyə əlavə/çıxma
     * edirik ki, nəticə HƏMİŞƏ tələb olunan ümumi uzunluğa tam
     * bərabər olsun. Bu, "defensiv proqramlaşdırma"dır — modelin
     * çıxışına kor-koranə güvənməmək.
     */
    private fun forceExactTotalDuration(scenes: List<Scene>, targetTotal: Int): List<Scene> {
        if (scenes.isEmpty()) return scenes
        val currentTotal = scenes.sumOf { it.durationSec }
        val diff = targetTotal - currentTotal
        if (diff == 0) return scenes

        val adjusted = scenes.toMutableList()
        val lastIndex = adjusted.lastIndex
        val newDuration = (adjusted[lastIndex].durationSec + diff).coerceAtLeast(1)
        adjusted[lastIndex] = adjusted[lastIndex].copy(durationSec = newDuration)
        return adjusted
    }

    /** Sinxron şəkildə çağırılır — mütləq bir background thread-dən (IO dispatcher) istifadə et. */
    fun generateScript(
        prompt: String,
        apiKey: String,
        totalDurationSeconds: Int
    ): Result<List<Scene>> {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey"

        val systemInstruction = buildSystemInstruction(totalDurationSeconds)
        val fullPrompt = "$systemInstruction\n\nUser Idea: $prompt"
        val bodyJson = """{"contents": [{"parts": [{"text": ${Gson().toJson(fullPrompt)}}]}]}"""

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return Result.failure(IOException("Gemini API xətası (${response.code}): $respBody"))
                }

                val root = JsonParser.parseString(respBody).asJsonObject
                val rawText = root.getAsJsonArray("candidates")[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0].asJsonObject
                    .get("text").asString

                val cleaned = rawText.replace("```json", "").replace("```", "").trim()
                val scriptResp = Gson().fromJson(cleaned, ScriptResponse::class.java)
                    ?: return Result.failure(IOException("Gemini cavabı gözlənilən formatda deyil."))

                val scenes = scriptResp.scenes.map {
                    Scene(
                        sceneId = it.scene_id,
                        narrationText = it.narration_text,
                        visualPrompt = it.visual_prompt,
                        durationSec = it.duration_sec,
                        subtitleText = it.subtitle_text
                    )
                }

                Result.success(forceExactTotalDuration(scenes, totalDurationSeconds))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
