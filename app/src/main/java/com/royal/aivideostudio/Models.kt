package com.royal.aivideostudio

/** UI-da işlədilən, redaktə edilə bilən səhnə modeli */
data class Scene(
    val sceneId: Int,
    var narrationText: String,
    var visualPrompt: String,
    var durationSec: Int,
    var subtitleText: String,
    var audioFilePath: String? = null
)

/** Gemini-dən gələn xam JSON-un strukturu */
data class ScriptResponse(
    val scenes: List<SceneRaw>
)

data class SceneRaw(
    val scene_id: Int,
    val narration_text: String,
    val visual_prompt: String,
    val duration_sec: Int,
    val subtitle_text: String
)
