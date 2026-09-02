package com.royal.aivideostudio

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Mərhələ 3b: Mərhələ 3a-nın üzərinə fon musiqisi qarışdırma və hər
 * şəklə yavaş-yavaş yaxınlaşma effekti (Ken Burns) əlavə olunub.
 *
 * Hələ bir FFmpeg əmri ilə (2-ci səhnədə tətbiqin bağlanma problemini
 * həll edən memarlıq saxlanılıb).
 */
object VideoRenderer {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun render(
        context: Context,
        scenes: List<Scene>,
        styleModifier: String,
        widthPx: Int,
        heightPx: Int,
        musicFilePath: String?,
        onProgress: (String) -> Unit
    ): Result<Uri> {
        return try {
            val renderWidth: Int
            val renderHeight: Int
            if (widthPx >= heightPx) {
                renderWidth = 640
                renderHeight = 360
            } else {
                renderWidth = 360
                renderHeight = 640
            }
            val fps = 8

            val workDir = File(context.cacheDir, "render_${System.currentTimeMillis()}")
            workDir.mkdirs()

            val n = scenes.size
            val imageFiles = mutableListOf<File>()
            val audioPaths = mutableListOf<String>()

            scenes.forEachIndexed { index, scene ->
                onProgress("Səhnə ${index + 1}/$n hazırlanır...")

                val audioPath = scene.audioFilePath
                    ?: return Result.failure(
                        IOException("Səhnə ${index + 1} üçün səs yaradılmayıb — əvvəlcə bütün səhnələr üçün səs yarat.")
                    )

                val imageFile = File(workDir, "img_$index.jpg")
                val imageUrl = ImageProvider.buildUrl(scene.visualPrompt, styleModifier, renderWidth, renderHeight, scene.imageSeed)
                downloadFile(imageUrl, imageFile)

                if (!imageFile.exists() || imageFile.length() == 0L) {
                    return Result.failure(IOException("Səhnə ${index + 1} üçün şəkil düzgün endirilmədi."))
                }

                imageFiles.add(imageFile)
                audioPaths.add(audioPath)
            }

            onProgress("Video tək mərhələdə hazırlanır (bir neçə dəqiqə çəkə bilər)...")

            val durations = audioPaths.map { (getAudioDurationMs(it) / 1000.0).coerceAtLeast(1.0) }
            val totalDuration = durations.sum()
            val hasMusic = !musicFilePath.isNullOrBlank()

            val inputArgs = StringBuilder()
            imageFiles.forEachIndexed { i, imgFile ->
                inputArgs.append("-loop 1 -t ${durations[i]} -i \"${imgFile.absolutePath}\" ")
            }
            audioPaths.forEach { path ->
                inputArgs.append("-i \"$path\" ")
            }
            if (hasMusic) {
                // -stream_loop -1: musiqi videodan qısadırsa, sonuna qədər təkrarlanır
                inputArgs.append("-stream_loop -1 -i \"$musicFilePath\" ")
            }

            val filterBuilder = StringBuilder()
            // Hər şəklə yavaş-yavaş yaxınlaşma effekti (Ken Burns) — statik
            // şəkil hissi qalmasın deyə. Əvvəlcə böyük ölçüyə "scale" edirik
            // ki, yaxınlaşma zamanı şəkil bulanıq görünməsin.
            for (i in 0 until n) {
                val frames = (durations[i] * fps).roundToInt().coerceAtLeast(1)
                filterBuilder.append(
                    "[$i:v]scale=1280:-2,zoompan=z='min(zoom+0.0015,1.3)':d=$frames:s=${renderWidth}x$renderHeight:fps=$fps," +
                        "setsar=1,format=yuv420p[v$i];"
                )
            }
            for (i in 0 until n) {
                val audioInputIndex = n + i
                filterBuilder.append(
                    "[$audioInputIndex:a]aformat=sample_rates=44100:channel_layouts=stereo[a$i];"
                )
            }
            val videoLabels = (0 until n).joinToString("") { "[v$it]" }
            val audioLabels = (0 until n).joinToString("") { "[a$it]" }
            filterBuilder.append("${videoLabels}concat=n=$n:v=1:a=0[vout];")
            filterBuilder.append("${audioLabels}concat=n=$n:v=0:a=1[aout]")

            val finalAudioLabel: String
            if (hasMusic) {
                val musicInputIndex = 2 * n
                // Musiqini xeyli qısaldırıq (25% həcm) ki, səsləndirməni batırmasın,
                // sonra onu videonun tam uzunluğuna qədər kəsib səsləndirmə ilə qarışdırırıq.
                filterBuilder.append(
                    ";[$musicInputIndex:a]volume=0.25,atrim=0:$totalDuration,asetpts=PTS-STARTPTS[music]" +
                        ";[aout][music]amix=inputs=2:duration=first:dropout_transition=0[mixedaudio]"
                )
                finalAudioLabel = "[mixedaudio]"
            } else {
                finalAudioLabel = "[aout]"
            }

            val finalFile = File(workDir, "final_output.mp4")
            val cmd = "-y $inputArgs" +
                "-filter_complex \"$filterBuilder\" " +
                "-map \"[vout]\" -map \"$finalAudioLabel\" " +
                "-c:v libkvazaar -kvazaar-params preset=ultrafast " +
                "-pix_fmt yuv420p -c:a aac -b:a 128k \"${finalFile.absolutePath}\""

            val session = FFmpegKit.execute(cmd)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                val logDetail = session.allLogsAsString?.takeLast(1000)
                    ?: session.failStackTrace
                    ?: "naməlum"
                return Result.failure(
                    IOException("Render xətası (kod ${session.returnCode}): $logDetail")
                )
            }

            onProgress("Downloads qovluğuna yazılır...")
            val savedUri = saveToDownloads(context, finalFile)

            workDir.deleteRecursively()

            Result.success(savedUri)
        } catch (e: Throwable) {
            Result.failure(IOException(e.message ?: e.javaClass.simpleName ?: "Naməlum xəta"))
        }
    }

    private fun downloadFile(url: String, outFile: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Şəkil endirilə bilmədi (${response.code}).")
            response.body?.byteStream()?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Şəkil boş cavab qaytardı.")
        }
    }

    private fun getAudioDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
        } catch (e: Exception) {
            5000L
        } finally {
            retriever.release()
        }
    }

    private fun saveToDownloads(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val fileName = "AIVideoStudio_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Fayl üçün yer yaradıla bilmədi.")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            return uri
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, fileName)
            file.copyTo(destFile, overwrite = true)
            return Uri.fromFile(destFile)
        }
    }
}
