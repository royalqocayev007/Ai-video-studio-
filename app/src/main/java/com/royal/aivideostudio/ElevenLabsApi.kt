package com.royal.aivideostudio

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs Text-to-Speech REST API-si ilə əlaqə.
 *
 * Gemini-dən fərqli olaraq, bu API JSON yox, birbaşa audio (mp3) bytes
 * qaytarır — ona görə cavabı JSON kimi oxumuruq, birbaşa fayla yazırıq.
 */
object ElevenLabsApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Sinxron çağırılır — mütləq Dispatchers.IO içindən istifadə et. */
    fun synthesizeToFile(
        text: String,
        apiKey: String,
        voiceId: String,
        outFile: File
    ): Result<Unit> {
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"

        val bodyJson = """
            {
              "text": ${Gson().toJson(text)},
              "model_id": "eleven_multilingual_v2",
              "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    return Result.failure(IOException("ElevenLabs xətası (${response.code}): $errBody"))
                }
                val bytes = response.body?.bytes()
                    ?: return Result.failure(IOException("ElevenLabs boş cavab qaytardı."))
                outFile.writeBytes(bytes)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
