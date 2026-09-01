package com.royal.aivideostudio

/** UI-da işlədilən, redaktə edilə bilən səhnə modeli */
data class Scene(
    val sceneId: Int,
    var narrationText: String,
    var visualPrompt: String,
    var durationSec: Int,
    var subtitleText: String,
    var audioFilePath: String? = null,
    var imageSeed: Int = 0
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

/**
 * Vizual üslub seçimləri.
 *
 * `promptModifier` şəkil generasiyası zamanı Gemini-nin verdiyi
 * `visual_prompt`-un sonuna əlavə olunur — beləliklə eyni səhnə
 * məzmunu fərqli üslublarda göstərilə bilər, üslub dəyişəndə
 * ssenarini yenidən yaratmağa ehtiyac qalmır.
 */
enum class VideoStyle(val displayName: String, val promptModifier: String) {
    CINEMATIC(
        "Kinematik",
        "cinematic, dramatic lighting, film still, wide shot, movie scene"
    ),
    CARTOON(
        "Cizgi film",
        "cartoon style, animated illustration, colorful, vector art, flat shading"
    ),
    REALISTIC(
        "Realistik foto",
        "photorealistic, high detail, DSLR photo, natural lighting, sharp focus"
    ),
    ANIMATION_3D(
        "3D animasiya",
        "3D rendered, CGI, Pixar style animation, octane render, soft studio lighting"
    ),
    MINIMALIST(
        "Minimalist",
        "minimalist flat design, simple shapes, clean background, vector, negative space"
    ),
    DIGITAL_ILLUSTRATION(
        "Rəqəmsal İllüstrasiya",
        "semi-realistic digital illustration, webtoon style, soft cel shading, clean linework, vibrant colors, detailed background"
    ),
    BW_SKETCH(
        "Qara-Ağ Eskiz",
        "black and white pencil sketch, monochrome ink drawing, hand-drawn doodle, comic book sketch art, NOT a photo, NOT photorealistic, illustration only, no color, rough linework, white paper background"
    );

    companion object {
        val displayNames = entries.map { it.displayName }

        fun fromDisplayName(name: String): VideoStyle =
            entries.find { it.displayName == name } ?: CINEMATIC
    }
}

/**
 * Səsləndirmə mənbəyi seçimi. DEVICE — telefonun daxili, pulsuz
 * mühərriki (indiyə qədər istifadə etdiyimiz). ELEVENLABS — bulud
 * əsaslı, daha keyfiyyətli, pullu servis; ayrıca API açarı tələb edir.
 */
enum class VoiceProvider(val displayName: String) {
    DEVICE("Telefonun öz səsi (pulsuz)"),
    ELEVENLABS("ElevenLabs (keyfiyyətli, pullu)");

    companion object {
        val displayNames = entries.map { it.displayName }

        fun fromDisplayName(name: String): VoiceProvider =
            entries.find { it.displayName == name } ?: DEVICE
    }
}

/**
 * Fon musiqisi mənbəyi. Hələlik yalnız OWN_UPLOAD tam işləkdir —
 * STOCK və SUNO növbəti mərhələdə (xüsusi API araşdırması lazımdır)
 * tam əlavə olunacaq, indi seçim kimi görünür amma "tezliklə" yazır.
 */
enum class MusicSource(val displayName: String) {
    NONE("Musiqi yoxdur"),
    OWN_UPLOAD("Öz musiqim (yüklə)"),
    STOCK("Stok musiqi (Pixabay)"),
    SUNO("Suno ilə yarat");

    companion object {
        val displayNames = entries.map { it.displayName }

        fun fromDisplayName(name: String): MusicSource =
            entries.find { it.displayName == name } ?: NONE
    }
}

/**
 * Tam bir layihənin "anlıq görüntüsü" (snapshot) — saxlanılıb sonra
 * geri yüklənə bilsin deyə. Bütün parametrləri və səhnələri özündə
 * daşıyır ki, layihəni yükləyəndə hər şey olduğu kimi geri gəlsin.
 */
data class Project(
    val name: String,
    val savedAt: Long,
    val idea: String,
    val durationSeconds: Int,
    val styleName: String,
    val aspect: String,
    val language: String,
    val scenes: List<Scene>,
    val currentSceneIndex: Int,
    val musicFilePath: String? = null
)
