package com.royal.aivideostudio

import android.content.Context
import java.io.File
import java.util.Locale

/** Orijinal koddakı SRT ixracı məntiqinin qarşılığı. */
object SrtExporter {

    fun buildSrt(scenes: List<Scene>): String {
        val sb = StringBuilder()
        var curTime = 0.0
        scenes.forEachIndexed { index, scene ->
            val start = curTime
            val end = curTime + scene.durationSec
            curTime = end
            sb.append(index + 1).append('\n')
            sb.append(fmt(start)).append(" --> ").append(fmt(end)).append('\n')
            sb.append(scene.subtitleText).append("\n\n")
        }
        return sb.toString()
    }

    private fun fmt(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong()
        val h = totalMs / 3_600_000
        val m = (totalMs % 3_600_000) / 60_000
        val s = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    fun saveToFile(context: Context, content: String, fileName: String = "subtitles.srt"): File {
        val file = File(context.filesDir, fileName)
        file.writeText(content)
        return file
    }
}
