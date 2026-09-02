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

/**
 * Mərhələ 3a: hər səhnənin şəklini onun səs faylı ilə birləşdirib,
 * hamısını bir MP4-də ard-arda düzür. Hələ fon musiqisi və altyazı yoxdur
 * — bunlar sonrakı addımlarda əlavə olunacaq.
 *
 * DİQQƏT (versiya 2): əvvəlki versiya hər səhnə üçün AYRI-AYRI FFmpeg
 * çağırışı edirdi (N dəfə). Bu, bəzi telefonlarda 2-ci səhnədə tətbiqin
 * tam bağlanmasına səbəb oldu — kitabxananın öz daxili (native) hissəsi
 * ard-arda tez-tez çağırılanda sabitliyini itirir. Bunun həlli: bütün
 * səhnələri TƏK BİR FFmpeg əmri ilə, bir dəfəyə emal etmək (bir
 * "filter_complex" — süzgəc zənciri — vasitəsilə).
 */
object VideoRenderer {

    // Pollinations bəzən yeni şəkil yaratmaq üçün 10-30+ saniyə vaxt apara
    // bilir, ona görə defolt (10 san.) gözləmə vaxtı bura kifayət etmir.
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

            // Hər şəklin ekranda nə qədər qalacağını onun səsinin HƏQİQİ
            // uzunluğuna görə təyin edirik.
            val durations = audioPaths.map { (getAudioDurationMs(it) / 1000.0).coerceAtLeast(1.0) }

            val inputArgs = StringBuilder()
            imageFiles.forEachIndexed { i, imgFile ->
                inputArgs.append("-loop 1 -t ${durations[i]} -i \"${imgFile.absolutePath}\" ")
            }
            audioPaths.forEach { path ->
                inputArgs.append("-i \"$path\" ")
            }

            val filterBuilder = StringBuilder()
            for (i in 0 until n) {
                filterBuilder.append(
                    "[$i:v]scale=$renderWidth:$renderHeight,fps=8,setsar=1,format=yuv420p[v$i];"
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

            val finalFile = File(workDir, "final_output.mp4")
            val cmd = "-y $inputArgs" +
                "-filter_complex \"$filterBuilder\" " +
                "-map \"[vout]\" -map \"[aout]\" " +
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

    /** Nəticə MP4-ü telefonun "Downloads" qovluğuna yazır ki, asanlıqla tapılıb paylaşıla bilsin. */
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
