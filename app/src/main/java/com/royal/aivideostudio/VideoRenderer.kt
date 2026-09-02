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
 * Necə işləyir (addım-addım):
 * 1. Hər səhnənin şəklini (Pollinations-dan) telefonun öz yaddaşına endiririk
 *    — FFmpeg uzaq linklərlə deyil, yerli fayllarla işləməlidir.
 * 2. Hər səhnənin səs faylının HƏQİQİ uzunluğunu ölçürük (TTS bəzən
 *    planlaşdırılan saniyədən bir az fərqli danışa bilər) — şəkil bu
 *    dəqiq müddət qədər ekranda qalacaq ki, səs kəsilməsin.
 * 3. Hər səhnə üçün ayrıca kiçik bir video "parçası" yaradırıq.
 * 4. Bütün parçaları bir-birinin ardınca birləşdirib tək fayl edirik.
 * 5. Nəticəni telefonun "Downloads" qovluğuna yazırıq ki, tapmaq asan olsun.
 */
object VideoRenderer {

    // Pollinations bəzən yeni şəkil yaratmaq üçün 10-30+ saniyə vaxt apara
    // bilir (xüsusən əvvəllər önizləmədə göstərilməmiş, təzə sorğu olanda),
    // ona görə defolt (10 san.) gözləmə vaxtı bura kifayət etmir.
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
            // Yaddaş (RAM) təzyiqini azaltmaq üçün render zamanı daha KİÇİK
            // ölçüdən istifadə edirik (ekranda göstərilən önizləmə fərqli ola
            // bilər, problem deyil — son videonun keyfiyyəti yenə də yaxşıdır,
            // sadəcə zəif telefonlarda "yaddaş bitdi" qəzasının qarşısını alır).
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

            val clipFiles = mutableListOf<File>()

            scenes.forEachIndexed { index, scene ->
                onProgress("Səhnə ${index + 1}/${scenes.size} hazırlanır...")

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

                val durationSec = (getAudioDurationMs(audioPath) / 1000.0).coerceAtLeast(1.0)

                val clipFile = File(workDir, "clip_$index.mp4")
                val cmd = "-y -loop 1 -i \"${imageFile.absolutePath}\" -i \"$audioPath\" " +
                    "-c:v libkvazaar -kvazaar-params preset=ultrafast " +
                    "-t $durationSec -vf \"scale=$renderWidth:$renderHeight,fps=8\" " +
                    "-pix_fmt yuv420p -c:a aac -b:a 128k \"${clipFile.absolutePath}\""

                onProgress("Səhnə ${index + 1}/${scenes.size} kodlaşdırılır (bir az vaxt apara bilər)...")
                val session = FFmpegKit.execute(cmd)
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    val logDetail = session.allLogsAsString?.takeLast(800)
                        ?: session.failStackTrace
                        ?: "naməlum"
                    return Result.failure(
                        IOException("Səhnə ${index + 1} render xətası (kod ${session.returnCode}): $logDetail")
                    )
                }

                // Şəkli dərhal silirik ki, boşuna yer/yaddaş tutmasın —
                // növbəti səhnəyə keçməzdən əvvəl təmizlik.
                imageFile.delete()
                clipFiles.add(clipFile)
                System.gc()
                // Növbəti səhnəyə keçməzdən əvvəl kiçik fasilə — kitabxananın
                // daxili (native) yaddaşının tam boşalmasına vaxt verir.
                // Bəzi telefonlarda ard-arda çox tez FFmpeg çağırışı sabitliyi
                // pozur, bu fasilə onun qarşısını almağa kömək edir.
                Thread.sleep(700)
            }

            onProgress("Bütün səhnələr birləşdirilir...")

            val concatListFile = File(workDir, "concat_list.txt")
            concatListFile.writeText(clipFiles.joinToString("\n") { "file '${it.absolutePath}'" })

            val finalFile = File(workDir, "final_output.mp4")
            val concatCmd = "-y -f concat -safe 0 -i \"${concatListFile.absolutePath}\" -c copy \"${finalFile.absolutePath}\""
            val concatSession = FFmpegKit.execute(concatCmd)
            if (!ReturnCode.isSuccess(concatSession.returnCode)) {
                val logDetail = concatSession.allLogsAsString?.takeLast(800)
                    ?: concatSession.failStackTrace
                    ?: "naməlum"
                return Result.failure(
                    IOException("Birləşdirmə xətası (kod ${concatSession.returnCode}): $logDetail")
                )
            }

            onProgress("Downloads qovluğuna yazılır...")
            val savedUri = saveToDownloads(context, finalFile)

            // Müvəqqəti iş qovluğunu təmizləyirik (istifadə olunmuş yaddaşı boşaltmaq üçün)
            workDir.deleteRecursively()

            Result.success(savedUri)
        } catch (e: Throwable) {
            // Throwable tuturuq (təkcə Exception yox) ki, "yaddaş bitdi"
            // (OutOfMemoryError) kimi daha ciddi hallar da tətbiqi
            // qəflətən bağlamasın, əvəzinə ekranda mesaj göstərilsin.
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
