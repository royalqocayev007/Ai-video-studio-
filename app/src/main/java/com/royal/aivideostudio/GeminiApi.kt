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
 * Orijinal Python koddakı generate_script_with_gemini funksiyasının
 * Kotlin/Android qarşılığı.
 */
object GeminiApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_INSTRUCTION =
        "You are an AI video scriptwriter. Respond ONLY with a valid JSON format. " +
            "Return a JSON object with a 'scenes' list containing 3 items. " +
            "Each item must have: 'scene_id', 'narration_text', 'visual_prompt' (in English), " +
            "'duration_sec' (integer), 'subtitle_text'."

    /** Sinxron şəkildə çağırılır — mütləq bir background thread-dən (IO dispatcher) istifadə et. */
    fun generateScript(prompt: String, apiKey: String): Result<List<Scene>> {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val fullPrompt = "$SYSTEM_INSTRUCTION\n\nUser Idea: $prompt"
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
                Result.success(scenes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
