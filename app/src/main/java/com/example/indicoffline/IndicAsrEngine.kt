package com.example.indicoffline

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline speech recognition. Model bytes are resolved through
 * [AsrModelRepository]; the caller only needs a language code.
 */
class IndicAsrEngine(private val modelRepository: AsrModelRepository) {
    private var recognizer: OfflineRecognizer? = null
    var currentLanguageCode = ""
        private set

    private val loadMutex = Mutex()
    private val recognizerLock = Any()

    /**
     * Loads the recognizer for [languageCode], installing its model files
     * first if needed. Returns false (and keeps the previous recognizer
     * active) if the model could not be made available.
     */
    suspend fun loadLanguage(languageCode: String): Boolean {
        if (currentLanguageCode == languageCode) return true

        val modelDir = modelRepository.ensureInstalled(languageCode) ?: run {
            android.util.Log.e("IndicAsrEngine", "No ASR model available for '$languageCode'")
            return false
        }

        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = File(modelDir, "model.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                debug = false
            ),
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        )

        return loadMutex.withLock {
            if (currentLanguageCode == languageCode) return true
            val newRecognizer = OfflineRecognizer(config = config)
            synchronized(recognizerLock) {
                recognizer?.release()
                recognizer = newRecognizer
            }
            currentLanguageCode = languageCode
            true
        }
    }

    @Synchronized
    fun transcribe(audioData: FloatArray): String {
        val activeRecognizer = recognizer ?: return "Engine not initialized"
        val stream = activeRecognizer.createStream()
        return try {
            stream.acceptWaveform(audioData, sampleRate = 16000)
            activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun destroy() {
        recognizer?.release()
        recognizer = null
        currentLanguageCode = ""
    }
}
