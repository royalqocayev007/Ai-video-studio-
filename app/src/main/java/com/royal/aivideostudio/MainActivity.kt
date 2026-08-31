package com.royal.aivideostudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var ttsHelper: TtsHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        createTts = { onReady ->
                            val helper = TtsHelper(this) { ok -> onReady(ok) }
                            ttsHelper = helper
                            helper
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        ttsHelper?.shutdown()
        super.onDestroy()
    }
}

// ===================== YADDAŞLA İŞ (SharedPreferences köməkçiləri) =====================
//
// Bunların hamısı eyni məntiqlə işləyir: SharedPreferences yalnız sadə
// mətn/rəqəm saxlaya bilir, ona görə siyahı və obyektləri Gson ilə JSON
// mətninə çeviririk, sonra geri oxuyuruq.

private fun loadIdeaHistory(prefs: android.content.SharedPreferences): List<String> {
    val json = prefs.getString("idea_history", null) ?: return emptyList()
    return try {
        Gson().fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveIdeaToHistory(prefs: android.content.SharedPreferences, idea: String) {
    if (idea.isBlank()) return
    val updated = loadIdeaHistory(prefs).toMutableList()
    updated.remove(idea)
    updated.add(0, idea)
    prefs.edit().putString("idea_history", Gson().toJson(updated.take(10))).apply()
}

private fun loadProjects(prefs: android.content.SharedPreferences): List<Project> {
    val json = prefs.getString("saved_projects", null) ?: return emptyList()
    return try {
        Gson().fromJson(json, Array<Project>::class.java)?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun persistProjects(prefs: android.content.SharedPreferences, projects: List<Project>) {
    prefs.edit().putString("saved_projects", Gson().toJson(projects)).apply()
}

/** Eyni adla layihə varsa üstünə yazır, yoxdursa siyahının başına əlavə edir. */
private fun upsertProject(prefs: android.content.SharedPreferences, project: Project): List<Project> {
    val updated = loadProjects(prefs).toMutableList()
    updated.removeAll { it.name == project.name }
    updated.add(0, project)
    persistProjects(prefs, updated)
    return updated
}

private fun deleteProject(prefs: android.content.SharedPreferences, name: String): List<Project> {
    val updated = loadProjects(prefs).toMutableList()
    updated.removeAll { it.name == name }
    persistProjects(prefs, updated)
    return updated
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(millis)

// ===================== ƏSAS EKRAN =====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(createTts: ((Boolean) -> Unit) -> TtsHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember {
        context.getSharedPreferences("ai_video_studio_prefs", android.content.Context.MODE_PRIVATE)
    }

    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf("Azərbaycan") }
    var aspect by remember { mutableStateOf("16:9 (YouTube)") }
    var durationText by remember { mutableStateOf("30") }
    var style by remember { mutableStateOf(VideoStyle.CINEMATIC) }
    var voiceProvider by remember {
        mutableStateOf(VoiceProvider.fromDisplayName(prefs.getString("voice_provider", "") ?: ""))
    }
    var elevenLabsApiKey by remember { mutableStateOf(prefs.getString("elevenlabs_api_key", "") ?: "") }
    var elevenLabsVoiceId by remember {
        mutableStateOf(prefs.getString("elevenlabs_voice_id", "21m00Tcm4TlvDq8ikWAM") ?: "21m00Tcm4TlvDq8ikWAM")
    }
    var idea by remember { mutableStateOf(prefs.getString("last_idea", "") ?: "") }
    var ideaHistory by remember { mutableStateOf(loadIdeaHistory(prefs)) }
    var isLoading by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var scenes by remember { mutableStateOf<List<Scene>>(emptyList()) }
    var currentSceneIndex by remember { mutableStateOf(0) }

    var projects by remember { mutableStateOf(loadProjects(prefs)) }
    var showProjectList by remember { mutableStateOf(false) }
    var saveNameInput by remember { mutableStateOf("") }

    var ttsReady by remember { mutableStateOf(false) }
    var ttsHelper by remember { mutableStateOf<TtsHelper?>(null) }

    LaunchedEffect(Unit) {
        ttsHelper = createTts { ok -> ttsReady = ok }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("🎬 AI Video Studio", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // ---------- LAYİHƏLƏR (saxlanmışlar) ----------
        if (projects.isNotEmpty()) {
            TextButton(onClick = { showProjectList = !showProjectList }) {
                Text(if (showProjectList) "📁 Layihələri Gizlət ▲" else "📁 Saxlanmış Layihələr (${projects.size}) ▼")
            }
            if (showProjectList) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    projects.forEach { project ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(project.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${formatDate(project.savedAt)} • ${project.scenes.size} səhnə",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = {
                                    idea = project.idea
                                    prefs.edit().putString("last_idea", project.idea).apply()
                                    durationText = project.durationSeconds.toString()
                                    style = VideoStyle.fromDisplayName(project.styleName)
                                    aspect = project.aspect
                                    language = project.language
                                    scenes = project.scenes
                                    currentSceneIndex = project.currentSceneIndex
                                    isError = false
                                    infoMsg = "\"${project.name}\" layihəsi yükləndi."
                                    showProjectList = false
                                }) {
                                    Text("Yüklə")
                                }
                                TextButton(onClick = {
                                    projects = deleteProject(prefs, project.name)
                                }) {
                                    Text("Sil", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
        }

        Text("⚙️ Parametrlər", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { newValue ->
                apiKey = newValue
                prefs.edit().putString("gemini_api_key", newValue).apply()
            },
            label = { Text("🔑 Gemini API Key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "🙈" else "👁")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        DropdownSelector("🌐 Dil", listOf("Azərbaycan", "English", "Türkçe", "Русский"), language) {
            language = it
        }

        Spacer(Modifier.height(8.dp))
        DropdownSelector(
            "📐 Video Formati",
            listOf("16:9 (YouTube)", "9:16 (Shorts/Reels)"),
            aspect
        ) { aspect = it }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = durationText,
            onValueChange = { new -> if (new.length <= 4 && new.all { it.isDigit() }) durationText = new },
            label = { Text("⏱️ Video Uzunluğu (saniyə)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        DropdownSelector(
            "🎨 Vizual Üslub",
            VideoStyle.displayNames,
            style.displayName
        ) { selected -> style = VideoStyle.fromDisplayName(selected) }

        Spacer(Modifier.height(8.dp))
        DropdownSelector(
            "🎙️ Səs Provayderi",
            VoiceProvider.displayNames,
            voiceProvider.displayName
        ) { selected ->
            voiceProvider = VoiceProvider.fromDisplayName(selected)
            prefs.edit().putString("voice_provider", voiceProvider.displayName).apply()
        }

        if (voiceProvider == VoiceProvider.ELEVENLABS) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = elevenLabsApiKey,
                onValueChange = { newValue ->
                    elevenLabsApiKey = newValue
                    prefs.edit().putString("elevenlabs_api_key", newValue).apply()
                },
                label = { Text("🔑 ElevenLabs API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = elevenLabsVoiceId,
                onValueChange = { newValue ->
                    elevenLabsVoiceId = newValue
                    prefs.edit().putString("elevenlabs_voice_id", newValue).apply()
                },
                label = { Text("Voice ID") },
                placeholder = { Text("Məsələn: 21m00Tcm4TlvDq8ikWAM") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Voice ID-ni elevenlabs.io saytında \"Voice Library\" bölməsindən tapa bilərsən.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("💡 1. Video İdeyası", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = idea,
            onValueChange = { newValue ->
                idea = newValue
                prefs.edit().putString("last_idea", newValue).apply()
            },
            label = { Text("İdeyanı yazın") },
            placeholder = { Text("Məsələn: Süni intellektin kənd təsərrüfatında rolu...") },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        )

        if (ideaHistory.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("🕘 Son ideyalar:", style = MaterialTheme.typography.labelMedium)
            Column(modifier = Modifier.fillMaxWidth()) {
                ideaHistory.forEach { pastIdea ->
                    TextButton(
                        onClick = {
                            idea = pastIdea
                            prefs.edit().putString("last_idea", pastIdea).apply()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            pastIdea,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                infoMsg = null
                val durationInt = durationText.toIntOrNull()
                when {
                    apiKey.isBlank() -> {
                        infoMsg = "Zəhmət olmasa Gemini API açarını daxil edin!"
                        isError = true
                    }
                    idea.isBlank() -> {
                        infoMsg = "Zəhmət olmasa video ideyasını yazın!"
                        isError = true
                    }
                    durationInt == null || durationInt <= 0 -> {
                        infoMsg = "Video uzunluğunu düzgün saniyə ədədi kimi yazın (məs: 30)."
                        isError = true
                    }
                    else -> {
                        isLoading = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                GeminiApi.generateScript(idea, apiKey, durationInt)
                            }
                            isLoading = false
                            result.onSuccess { rawScenes ->
                                scenes = rawScenes.mapIndexed { idx, s -> s.copy(imageSeed = idx * 42) }
                                currentSceneIndex = 0
                                isError = false
                                saveIdeaToHistory(prefs, idea)
                                ideaHistory = loadIdeaHistory(prefs)
                                val totalCheck = scenes.sumOf { s -> s.durationSec }
                                infoMsg = "Ssenari hazırlandı! (${scenes.size} səhnə, cəmi $totalCheck saniyə)"
                            }.onFailure {
                                isError = true
                                infoMsg = it.message ?: "Naməlum xəta baş verdi."
                            }
                        }
                    }
                }
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Yaradılır..." else "✨ Ssenari Yarat")
        }

        infoMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }

        if (scenes.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))

            // ---------- LAYİHƏNİ SAXLA ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("💾 Bu Layihəni Saxla", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = saveNameInput,
                        onValueChange = { saveNameInput = it },
                        label = { Text("Layihə adı") },
                        placeholder = { Text("Məsələn: Bakı videosu") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val name = saveNameInput.trim()
                            if (name.isBlank()) {
                                infoMsg = "Layihə üçün bir ad yaz."
                                isError = true
                            } else {
                                val durationInt = durationText.toIntOrNull() ?: 30
                                val project = Project(
                                    name = name,
                                    savedAt = System.currentTimeMillis(),
                                    idea = idea,
                                    durationSeconds = durationInt,
                                    styleName = style.displayName,
                                    aspect = aspect,
                                    language = language,
                                    scenes = scenes,
                                    currentSceneIndex = currentSceneIndex
                                )
                                projects = upsertProject(prefs, project)
                                isError = false
                                infoMsg = "\"$name\" layihəsi saxlanıldı."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("💾 Saxla")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("🎞️ 2. Səhnələr və Generasiya", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val doneCount = minOf(currentSceneIndex, scenes.size)
            Text(
                "İrəliləyiş: $doneCount / ${scenes.size} səhnə tamamlandı",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { doneCount / scenes.size.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            val (w, h) = if (aspect.startsWith("16:9")) 1280 to 720 else 720 to 1280

            scenes.forEachIndexed { index, scene ->
                Spacer(Modifier.height(12.dp))
                when {
                    index < currentSceneIndex -> {
                        CompletedSceneRow(
                            index = index,
                            scene = scene,
                            styleModifier = style.promptModifier,
                            width = w,
                            height = h,
                            onEdit = { currentSceneIndex = index }
                        )
                    }
                    index == currentSceneIndex -> {
                        ActiveSceneCard(
                            index = index,
                            scene = scene,
                            width = w,
                            height = h,
                            styleModifier = style.promptModifier,
                            isLastScene = index == scenes.lastIndex,
                            ttsReady = if (voiceProvider == VoiceProvider.ELEVENLABS) true else ttsReady,
                            onSceneChange = { updated ->
                                scenes = scenes.toMutableList().also { it[index] = updated }
                            },
                            onRegenerateImage = {
                                val updated = scene.copy(imageSeed = scene.imageSeed + 1)
                                scenes = scenes.toMutableList().also { it[index] = updated }
                            },
                            onConfirmNext = {
                                currentSceneIndex += 1
                            },
                            onGenerateAudio = {
                                if (voiceProvider == VoiceProvider.ELEVENLABS) {
                                    if (elevenLabsApiKey.isBlank() || elevenLabsVoiceId.isBlank()) {
                                        infoMsg = "ElevenLabs API açarı və Voice ID daxil edilməlidir."
                                        isError = true
                                        return@ActiveSceneCard
                                    }
                                    val outFile = File(context.filesDir, "scene_${index + 1}.mp3")
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ElevenLabsApi.synthesizeToFile(
                                                scene.narrationText,
                                                elevenLabsApiKey,
                                                elevenLabsVoiceId,
                                                outFile
                                            )
                                        }
                                        result.onSuccess {
                                            val updated = scene.copy(audioFilePath = outFile.absolutePath)
                                            scenes = scenes.toMutableList().also { it[index] = updated }
                                        }.onFailure {
                                            infoMsg = it.message ?: "ElevenLabs səs yaradıla bilmədi."
                                            isError = true
                                        }
                                    }
                                    return@ActiveSceneCard
                                }

                                val helper = ttsHelper
                                if (helper == null) {
                                    infoMsg = "Səs mühərriki hələ hazır deyil."
                                    isError = true
                                    return@ActiveSceneCard
                                }
                                val locale = helper.localeFor(language)
                                if (!helper.isLanguageSupported(locale)) {
                                    infoMsg =
                                        "Bu dil üçün cihazda səs mühərriki quraşdırılmayıb " +
                                            "(Tənzimləmələr > Dil > Mətndən nitqə bölməsindən əlavə et)."
                                    isError = true
                                    return@ActiveSceneCard
                                }
                                val outFile = File(context.filesDir, "scene_${index + 1}.wav")
                                helper.synthesizeToFile(scene.narrationText, locale, outFile) { ok ->
                                    scope.launch(Dispatchers.Main) {
                                        if (ok) {
                                            val updated = scene.copy(audioFilePath = outFile.absolutePath)
                                            scenes = scenes.toMutableList().also { it[index] = updated }
                                        } else {
                                            infoMsg = "Səhnə ${index + 1} üçün səs yaradıla bilmədi."
                                            isError = true
                                        }
                                    }
                                }
                            }
                        )
                    }
                    else -> {
                        // Növbədə gözləyir — hələ ekranda göstərmirik.
                    }
                }
            }

            if (currentSceneIndex >= scenes.size) {
                Spacer(Modifier.height(16.dp))
                Text("✅ Bütün səhnələr tamamlandı!", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        val srt = SrtExporter.buildSrt(scenes)
                        val file = SrtExporter.saveToFile(context, srt)
                        isError = false
                        infoMsg = "SRT faylı saxlanıldı: ${file.absolutePath}"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📄 SRT Subtitr Faylını Yarat")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** Artıq təsdiqlənmiş səhnə üçün yığcam sətir — üstünə basıb geri qayıda bilər. */
@Composable
fun CompletedSceneRow(
    index: Int,
    scene: Scene,
    styleModifier: String,
    width: Int,
    height: Int,
    onEdit: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val imageUrl = remember(scene.visualPrompt, styleModifier, scene.imageSeed) {
                ImageProvider.buildUrl(scene.visualPrompt, styleModifier, width, height, scene.imageSeed)
            }
            AsyncImage(
                model = imageUrl,
                contentDescription = "Səhnə ${index + 1} vizualı",
                modifier = Modifier
                    .width(64.dp)
                    .height(64.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("✅ Səhnə ${index + 1}  •  ${scene.durationSec} san.", style = MaterialTheme.typography.labelMedium)
                Text(
                    scene.narrationText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onEdit) {
                Text("Düzəlt")
            }
        }
    }
}

/** Hazırda üzərində işlənən (aktiv) səhnə — tam redaktə imkanı ilə. */
@Composable
fun ActiveSceneCard(
    index: Int,
    scene: Scene,
    width: Int,
    height: Int,
    styleModifier: String,
    isLastScene: Boolean,
    ttsReady: Boolean,
    onSceneChange: (Scene) -> Unit,
    onRegenerateImage: () -> Unit,
    onConfirmNext: () -> Unit,
    onGenerateAudio: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "🎬 Səhnə ${index + 1}  •  ${scene.durationSec} san.  (aktiv)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            val imageUrl = remember(scene.visualPrompt, styleModifier, scene.imageSeed, width, height) {
                ImageProvider.buildUrl(scene.visualPrompt, styleModifier, width, height, scene.imageSeed)
            }
            AsyncImage(
                model = imageUrl,
                contentDescription = "Səhnə ${index + 1} vizualı",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRegenerateImage,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔄 Şəkli Yenidən Yarat")
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = scene.narrationText,
                onValueChange = { onSceneChange(scene.copy(narrationText = it)) },
                label = { Text("Səs Mətni #${index + 1}") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = scene.visualPrompt,
                onValueChange = { onSceneChange(scene.copy(visualPrompt = it)) },
                label = { Text("İngiliscə Prompt #${index + 1}") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = scene.subtitleText,
                onValueChange = { onSceneChange(scene.copy(subtitleText = it)) },
                label = { Text("Subtitr #${index + 1}") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onGenerateAudio,
                enabled = ttsReady,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🎙️ Səsi Yarat #${index + 1}")
            }

            scene.audioFilePath?.let {
                Spacer(Modifier.height(4.dp))
                Text("✅ Yaradıldı: ${File(it).name}", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirmNext,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLastScene) "✅ Təsdiqlə, Bitir" else "✅ Təsdiqlə, Növbəti Səhnə →")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
