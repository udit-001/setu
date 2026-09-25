package com.example.indicoffline

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import androidx.core.content.edit

data class ConversationMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalText: String,
    val translatedText: String,
    val speakerLang: String,
    val targetLang: String,
    val isPrimaryUser: Boolean,
    val transcriptionTimeMs: Long = 0L,
    val translationTimeMs: Long = 0L
)

class TranslationViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("indic_offline_prefs", Context.MODE_PRIVATE)

    private val asrModels = AsrModelRepository(
        modelsDir = File(application.filesDir, "asr_models"),
        fetcher = WorkManagerModelFetcher(application)
    )
    private val asrEngine = IndicAsrEngine(asrModels)
    private var llamaCtx: Long = 0L
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _isModelDownloaded = MutableStateFlow(ModelDownloader.isModelDownloaded(application))
    val isModelDownloaded: StateFlow<Boolean> = _isModelDownloaded.asStateFlow()

    private val _downloadFailed = MutableStateFlow(false)
    val downloadFailed: StateFlow<Boolean> = _downloadFailed.asStateFlow()

    private val _downloadWaitingForNetwork = MutableStateFlow(false)
    val downloadWaitingForNetwork: StateFlow<Boolean> = _downloadWaitingForNetwork.asStateFlow()

    fun retryModelDownload() {
        _downloadFailed.value = false
        _downloadWaitingForNetwork.value = false
        _downloadProgress.value = 0
        ModelDownloader.enqueueDownload(getApplication())
    }

    private val _isDarkMode = MutableStateFlow(
        if (prefs.contains("is_dark_mode")) prefs.getBoolean("is_dark_mode", false) else null
    )
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    fun setDarkMode(isDark: Boolean) {
        _isDarkMode.value = isDark
        prefs.edit { putBoolean("is_dark_mode", isDark) }
    }

    private val _isHapticsEnabled = MutableStateFlow(prefs.getBoolean("is_haptics_enabled", true))
    val isHapticsEnabled: StateFlow<Boolean> = _isHapticsEnabled.asStateFlow()

    fun setHapticsEnabled(enabled: Boolean) {
        _isHapticsEnabled.value = enabled
        prefs.edit { putBoolean("is_haptics_enabled", enabled) }
    }

    private val _showNerdStats = MutableStateFlow(prefs.getBoolean("show_nerd_stats", false))
    val showNerdStats: StateFlow<Boolean> = _showNerdStats.asStateFlow()

    fun setShowNerdStats(enabled: Boolean) {
        _showNerdStats.value = enabled
        prefs.edit { putBoolean("show_nerd_stats", enabled) }
    }

    private val _ttsSpeechSpeed = MutableStateFlow(prefs.getFloat("tts_speech_speed", 1.0f))
    val ttsSpeechSpeed: StateFlow<Float> = _ttsSpeechSpeed.asStateFlow()

    fun setTtsSpeechSpeed(speed: Float) {
        _ttsSpeechSpeed.value = speed
        prefs.edit { putFloat("tts_speech_speed", speed) }
        tts?.setSpeechRate(speed)
    }

    private val _srcLang = MutableStateFlow("hi")
    val srcLang: StateFlow<String> = _srcLang.asStateFlow()

    val availableLanguages: List<String> = listOf("hi", "kn", "ta", "te", "mr", "ml", "en")
    val asrModelStatus: StateFlow<Map<String, AsrModelStatus>> = asrModels.status
    
    private val _conversationHistory = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversationHistory: StateFlow<List<ConversationMessage>> = _conversationHistory.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

    private val _streamingTranslation = MutableStateFlow("")
    val streamingTranslation: StateFlow<String> = _streamingTranslation.asStateFlow()

    data class TranslationFailure(
        val originalText: String,
        val speakerLang: String,
        val targetLang: String,
        val transcriptionTimeMs: Long
    )

    private val _translationFailure = MutableStateFlow<TranslationFailure?>(null)
    val translationFailure: StateFlow<TranslationFailure?> = _translationFailure.asStateFlow()

    fun dismissTranslationFailure() {
        _translationFailure.value = null
    }

    private val _primaryLang = MutableStateFlow("hi")
    val primaryLang: StateFlow<String> = _primaryLang.asStateFlow()

    private val _secondaryLang = MutableStateFlow("kn")
    val secondaryLang: StateFlow<String> = _secondaryLang.asStateFlow()

    fun setPrimaryLang(lang: String) {
        val wasPrimary = _srcLang.value == _primaryLang.value
        _primaryLang.value = lang
        if (wasPrimary) {
            switchLanguage(lang)
        }
    }

    fun setSecondaryLang(lang: String) {
        val wasSecondary = _srcLang.value == _secondaryLang.value
        _secondaryLang.value = lang
        if (wasSecondary) {
            switchLanguage(lang)
        }
    }

    val targetLang: String
        get() = if (_srcLang.value == _primaryLang.value) _secondaryLang.value else _primaryLang.value
        
    fun getLanguageName(code: String): String {
        return when (code) {
            "hi" -> "हिन्दी"
            "kn" -> "ಕನ್ನಡ"
            "ta" -> "தமிழ்"
            "te" -> "తెలుగు"
            "mr" -> "मराठी"
            "ml" -> "മലയാളം"
            "en" -> "English"
            else -> "Unknown"
        }
    }

    fun getLanguageNameEnglish(code: String): String {
        return when (code) {
            "hi" -> "Hindi"
            "kn" -> "Kannada"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "mr" -> "Marathi"
            "ml" -> "Malayalam"
            "en" -> "English"
            else -> "Unknown"
        }
    }

    init {
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            asrEngine.loadLanguage("hi")
            
            val appCtx = getApplication<Application>()
            
            if (!_isModelDownloaded.value) {
                android.util.Log.d("LlamaTest", "Starting WorkManager download...")
                ModelDownloader.enqueueDownload(appCtx)
                
                launch {
                    androidx.work.WorkManager.getInstance(appCtx)
                        .getWorkInfosByTagFlow("model_download")
                        .collect { workInfoList ->
                            // Only the translation-model work (the ASR fetcher
                            // shares the "model_download" tag).
                            val workInfo = workInfoList.firstOrNull { it.tags.contains("translation_model") }
                                ?: return@collect
                            when (workInfo.state) {
                                androidx.work.WorkInfo.State.ENQUEUED ->
                                    _downloadWaitingForNetwork.value = true
                                androidx.work.WorkInfo.State.RUNNING -> {
                                    _downloadWaitingForNetwork.value = false
                                    val progress = workInfo.progress.getInt("PROGRESS", _downloadProgress.value)
                                    if (progress > _downloadProgress.value) {
                                        _downloadProgress.value = progress
                                    }
                                }
                                androidx.work.WorkInfo.State.SUCCEEDED -> {
                                    _downloadWaitingForNetwork.value = false
                                    if (!_isModelReady.value) {
                                        _isModelDownloaded.value = true
                                        _downloadProgress.value = 100
                                        val modelPath = ModelDownloader.getModelFile(appCtx).absolutePath
                                        if (llamaCtx == 0L) {
                                            val startLoadTime = System.currentTimeMillis()
                                            llamaCtx = LlamaWrapper.loadModel(modelPath)
                                            val loadTime = System.currentTimeMillis() - startLoadTime
                                            android.util.Log.d("LlamaTest", "Model load took ${loadTime}ms")
                                                                                    
                                            if (llamaCtx != 0L) _isModelReady.value = true
                                        }
                                    }
                                }
                                androidx.work.WorkInfo.State.FAILED,
                                androidx.work.WorkInfo.State.CANCELLED -> {
                                    _downloadWaitingForNetwork.value = false
                                    _downloadFailed.value = true
                                }
                                else -> {}
                            }
                        }
                }
            } else {
                _downloadProgress.value = 100
                val modelPath = ModelDownloader.getModelFile(appCtx).absolutePath
                if (llamaCtx == 0L) {
                    val startLoadTime = System.currentTimeMillis()
                    llamaCtx = LlamaWrapper.loadModel(modelPath)
                    val loadTime = System.currentTimeMillis() - startLoadTime
                                        
                    if (llamaCtx != 0L) {
                        _isModelReady.value = true
                    }
                }
            }
        }
    }

    fun switchLanguage(lang: String) {
        _srcLang.value = lang
        _transcription.value = ""
        _streamingTranslation.value = ""
        _translationFailure.value = null
        viewModelScope.launch(Dispatchers.IO) {
            asrEngine.loadLanguage(lang)
        }
    }

    fun startRecording(audioCapturer: AudioCapturer) {
        if (!asrModels.isReady(_srcLang.value)) {
            android.util.Log.w("TranslationViewModel", "Recording blocked: ASR model for '${_srcLang.value}' not ready")
            return
        }
        audioCapturer.startRecording()
        _isRecording.value = true
        _transcription.value = "Listening..."
        _streamingTranslation.value = ""
        _translationFailure.value = null
    }

    fun stopRecordingAndProcess(audioCapturer: AudioCapturer, onTtsMissing: (String) -> Unit) {
        _isRecording.value = false
        _isTranslating.value = true
        _transcription.value = "Processing..."
        _streamingTranslation.value = ""
        
        viewModelScope.launch(Dispatchers.IO) {
            val audioData = audioCapturer.stopAndGetFloatArray()
            val asrStart = System.currentTimeMillis()
            val resultText = asrEngine.transcribe(audioData)
            val asrTime = System.currentTimeMillis() - asrStart
            android.util.Log.d("LlamaTest", "Transcription: '$resultText'")
            
            withContext(Dispatchers.Main) {
                _transcription.value = resultText.ifEmpty { "No speech detected." }
            }
            
            if (resultText.isNotEmpty()) {
                val transStart = System.currentTimeMillis()
                val targetLangCode = targetLang
                val translated = translate(resultText, _srcLang.value, targetLangCode)
                val transTime = System.currentTimeMillis() - transStart
                android.util.Log.d("LlamaTest", "Translation: '$translated'")
                                
                withContext(Dispatchers.Main) {
                    _isTranslating.value = false
                    _transcription.value = ""

                    if (translated == null) {
                        // Failed translation: surface as a retryable error
                        // card, never as conversation content.
                        _streamingTranslation.value = ""
                        _translationFailure.value = TranslationFailure(
                            originalText = resultText,
                            speakerLang = _srcLang.value,
                            targetLang = targetLangCode,
                            transcriptionTimeMs = asrTime
                        )
                    } else {
                        val newMessage = ConversationMessage(
                            originalText = resultText,
                            translatedText = translated,
                            speakerLang = _srcLang.value,
                            targetLang = targetLangCode,
                            isPrimaryUser = _srcLang.value == _primaryLang.value,
                            transcriptionTimeMs = asrTime,
                            translationTimeMs = transTime
                        )
                        _conversationHistory.value += newMessage

                        speak(translated, targetLangCode, onTtsMissing)
                    }
                }
            } else {
                kotlinx.coroutines.delay(1500)
                withContext(Dispatchers.Main) {
                    _isTranslating.value = false
                    _transcription.value = ""
                }
            }
        }
    }

    fun speakTranslation(text: String, targetLangCode: String, onTtsMissing: (String) -> Unit) {
        speak(text, targetLangCode, onTtsMissing)
    }

    fun retryFailedTranslation(onTtsMissing: (String) -> Unit) {
        val failure = _translationFailure.value ?: return
        _translationFailure.value = null
        _isTranslating.value = true
        _transcription.value = failure.originalText
        _streamingTranslation.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            val transStart = System.currentTimeMillis()
            val translated = translate(failure.originalText, failure.speakerLang, failure.targetLang)
            val transTime = System.currentTimeMillis() - transStart

            withContext(Dispatchers.Main) {
                _isTranslating.value = false
                _transcription.value = ""
                if (translated == null) {
                    _translationFailure.value = failure
                } else {
                    _conversationHistory.value += ConversationMessage(
                        originalText = failure.originalText,
                        translatedText = translated,
                        speakerLang = failure.speakerLang,
                        targetLang = failure.targetLang,
                        isPrimaryUser = failure.speakerLang == _primaryLang.value,
                        transcriptionTimeMs = failure.transcriptionTimeMs,
                        translationTimeMs = transTime
                    )
                    speak(translated, failure.targetLang, onTtsMissing)
                }
            }
        }
    }

    private fun speak(text: String, targetLangCode: String, onTtsMissing: (String) -> Unit) {
        if (!isTtsReady || tts == null) return
        val engine = tts ?: return
        val lang = if (availableLanguages.contains(targetLangCode)) targetLangCode else "hi"

        // Android TTS packs are country-scoped (en-US, en-IN, ta-IN, ...); there
        // is no bare-language pack, and each country pack has its own install
        // state. Never sample an arbitrary voice for the language: pick the best
        // *installed* voice, preferring a region match, then an offline-capable
        // voice (this app must work with no signal).
        val preferredRegions = if (lang == "en") listOf("US", "IN", "GB", "AU") else listOf("IN")
        val installedVoices = engine.voices.orEmpty().filter {
            it.locale.language == lang &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
        val voice: Voice? = installedVoices.firstOrNull { v ->
            !v.isNetworkConnectionRequired && v.locale.country.uppercase() in preferredRegions
        } ?: installedVoices.firstOrNull { !it.isNetworkConnectionRequired }
            ?: installedVoices.firstOrNull()

        if (voice != null) {
            engine.setVoice(voice)
            android.util.Log.d("TtsDebug", "Using TTS voice '${voice.name}' (${voice.locale})")
        } else {
            // No installed voice reported: probe setLanguage across regions
            // before giving up (some engines don't populate getVoices()).
            val anyAvailable = preferredRegions.any { region ->
                val locale = Locale.Builder().setLanguage(lang).setRegion(region).build()
                val result = engine.setLanguage(locale)
                android.util.Log.d("TtsDebug", "setLanguage($locale) -> $result")
                result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            }
            if (!anyAvailable) {
                android.util.Log.d("TtsDebug", "No installed TTS voice for '$lang'")
                reportMissingVoice(targetLangCode, onTtsMissing)
                return
            }
        }

        engine.setSpeechRate(_ttsSpeechSpeed.value)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun reportMissingVoice(targetLangCode: String, onTtsMissing: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.Main) {
            val englishName = getLanguageNameEnglish(targetLangCode)
            val nativeName = getLanguageName(targetLangCode)
            onTtsMissing(if (englishName == nativeName) englishName else "$englishName ($nativeName)")
        }
    }

    /** Returns the translation, or null on failure (model not loaded, engine error, empty output). */
    private suspend fun translate(text: String, srcLang: String, targetLang: String): String? {
        if (llamaCtx == 0L) return null
        return withContext(Dispatchers.IO) {
            if (srcLang == "en" && targetLang == "en") return@withContext text

            // The open-sourced Sarvam-Translate checkpoint only supports
            // English <-> Indic directions, so Indic -> Indic must pivot via
            // English. When either side is already English, a single pass suffices.
            val sourceText = if (srcLang != "en" && targetLang != "en") {
                val toEnglishPrompt = "<bos><start_of_turn>user\nTranslate the text below to English.\n\n$text<end_of_turn>\n<start_of_turn>model\n"
                val englishBridge = LlamaWrapper.completion(llamaCtx, toEnglishPrompt).trim()
                android.util.Log.d("LlamaTest", "English bridge: '$englishBridge'")
                if (englishBridge.isEmpty() || englishBridge.startsWith("ERROR")) return@withContext null
                englishBridge
            } else {
                text
            }

            val targetLangName = getLanguageName(targetLang)
            val toTargetPrompt = "<bos><start_of_turn>user\nTranslate the text below to $targetLangName.\n\n$sourceText<end_of_turn>\n<start_of_turn>model\n"
            
            _streamingTranslation.value = ""
            val tokenChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            
            val typeWriterJob = launch {
                var displayed = ""
                for (token in tokenChannel) {
                    for (char in token) {
                        displayed += char
                        _streamingTranslation.value = displayed.trimStart()
                        kotlinx.coroutines.delay(30) 
                    }
                }
            }

            val sb = java.lang.StringBuilder()
            var streamFailed = false
            LlamaWrapper.generateStream(llamaCtx, toTargetPrompt).collect { token ->
                if (token.startsWith("ERROR")) {
                    streamFailed = true
                } else {
                    sb.append(token)
                    tokenChannel.trySend(token)
                }
            }
            
            tokenChannel.close()
            typeWriterJob.join()
            
            val finalTranslation = sb.toString().trim()
            _streamingTranslation.value = finalTranslation
            
            android.util.Log.d("LlamaTest", "Translation ($srcLang -> $targetLang): '$finalTranslation'")
            if (streamFailed || finalTranslation.isEmpty()) null else finalTranslation
        }
    }

    override fun onCleared() {
        super.onCleared()
        asrEngine.destroy()
        if (llamaCtx != 0L) LlamaWrapper.freeModel(llamaCtx)
        tts?.stop()
        tts?.shutdown()
    }
    
    fun submitFeedback(isPositive: Boolean) {    }
}
