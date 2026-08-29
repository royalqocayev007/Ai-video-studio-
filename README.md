# AI Video Studio — Android (native)

Bu, orijinal Streamlit (Python) tətbiqinin Kotlin/Jetpack Compose ilə yazılmış
tam native Android versiyasıdır.

## Necə açmalı / build etməli

1. **Android Studio** yüklə (pulsuzdur): https://developer.android.com/studio
2. Android Studio-da **Open** düyməsini basıb bu `AIVideoStudio` qovluğunu seç.
3. Gradle sync avtomatik başlayacaq (internet lazımdır — kitabxanaları yükləyəcək).
4. Real telefon (USB debug aktiv) və ya emulyator seçib **Run ▶** düyməsinə bas.
5. APK faylı lazımdırsa: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Nə dəyişdi (orijinal Python koddan fərqlər)

- **UI**: Streamlit əvəzinə Jetpack Compose (native Android ekranları).
- **Ssenari (Gemini)**: eyni məntiq, amma OkHttp ilə birbaşa Android-dən çağırılır.
- **Şəkillər**: Pollinations.ai eyni cür istifadə olunur (Coil kitabxanası ilə yüklənir).
- **Səs (TTS)**: `edge-tts` Python kitabxanasıdır, Android-də işləmir. Onun yerinə
  telefonun **daxili Mətndən-nitqə mühərriki** istifadə olunur.
  ⚠️ Azərbaycan dili bəzi telefonlarda quraşdırılmayıb ola bilər — belə halda
  Tənzimləmələr > Dil və Giriş > Mətndən nitqə bölməsindən əlavə etmək lazımdır.
- **SRT ixracı**: eyni məntiqlə, faylı tətbiqin öz qovluğuna (`filesDir`) yazır.

## Bilinən məhdudiyyətlər

- Gemini API açarı hazırda yaddaşda saxlanmır (hər açılışda yenidən yazılmalıdır).
  İstəsən, `EncryptedSharedPreferences` ilə təhlükəsiz saxlamağı əlavə edə bilərik.
- TTS səsinin keyfiyyəti cihazın öz mühərrikindən asılıdır (edge-tts-in neural
  səsi ilə eyni olmayacaq). Daha keyfiyyətli səs üçün Azure/Google Cloud TTS
  REST API-sinə keçmək mümkündür (ayrıca API açarı tələb edir).
- İnternet bağlantısı olmadan işləmir (Gemini, şəkil və s. hamısı bulud API-lərdir).
