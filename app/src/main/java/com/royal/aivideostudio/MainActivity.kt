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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(createTts: ((Boolean) -> Unit) -> TtsHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf("Azərbaycan") }
    var aspect by remember { mutableStateOf("16:9 (YouTube)") }
    var durationText by remember { mutableStateOf("30") }
    var style by remember { mutableStateOf(VideoStyle.CINEMATIC) }
    var idea by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var scenes by remember { mutableStateOf<List<Scene>>(emptyList()) }
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

        Text("⚙️ Parametrlər", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
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

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("💡 1. Video İdeyası", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = idea,
            onValueChange = { idea = it },
            label = { Text("İdeyanı yazın") },
            placeholder = { Text("Məsələn: Süni intellektin kənd təsərrüfatında rolu...") },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        )

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
                            result.onSuccess {
                                scenes = it
                                isError = false
                                val totalCheck = it.sumOf { s -> s.durationSec }
                                infoMsg = "Ssenari hazırlandı! (${it.size} səhnə, cəmi $totalCheck saniyə)"
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
            Spacer(Modifier.height(24.dp))
            Text("🎞️ 2. Səhnələr və Generasiya", style = MaterialTheme.typography.titleMedium)

            val (w, h) = if (aspect.startsWith("16:9")) 1280 to 720 else 720 to 1280

            scenes.forEachIndexed { index, scene ->
                Spacer(Modifier.height(12.dp))
                SceneCard(
                    index = index,
                    scene = scene,
                    width = w,
                    height = h,
                    styleModifier = style.promptModifier,
                    ttsReady = ttsReady,
                    onSceneChange = { updated ->
                        scenes = scenes.toMutableList().also { it[index] = updated }
                    },
                    onGenerateAudio = {
                        val helper = ttsHelper
                        if (helper == null) {
                            infoMsg = "Səs mühərriki hələ hazır deyil."
                            isError = true
                            return@SceneCard
                        }
                        val locale = helper.localeFor(language)
                        if (!helper.isLanguageSupported(locale)) {
                            infoMsg =
                                "Bu dil üçün cihazda səs mühərriki quraşdırılmayıb " +
                                    "(Tənzimləmələr > Dil > Mətndən nitqə bölməsindən əlavə et)."
                            isError = true
                            return@SceneCard
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

            Spacer(Modifier.height(16.dp))
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

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun SceneCard(
    index: Int,
    scene: Scene,
    width: Int,
    height: Int,
    styleModifier: String,
    ttsReady: Boolean,
    onSceneChange: (Scene) -> Unit,
    onGenerateAudio: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "🎬 Səhnə ${index + 1}  •  ${scene.durationSec} san.",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            val imageUrl = remember(scene.visualPrompt, styleModifier, width, height) {
                ImageProvider.buildUrl(scene.visualPrompt, styleModifier, width, height, index * 42)
            }
            AsyncImage(
                model = imageUrl,
                contentDescription = "Səhnə ${index + 1} vizualı",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

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
