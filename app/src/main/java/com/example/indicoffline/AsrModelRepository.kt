package com.example.indicoffline

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AsrModelStatus {
    data object NotInstalled : AsrModelStatus
    data class Downloading(val progressPercent: Int) : AsrModelStatus
    data object Ready : AsrModelStatus
}

/**
 * Owns the on-device lifecycle of ASR speech models: whether a language's
 * model files are installed, and fetching them on first use.
 *
 * Model bytes come from the network (GitHub LFS for the bundled Indic
 * languages, Hugging Face for English) — callers only ever deal with
 * language codes.
 */
class AsrModelRepository(
    private val modelsDir: File,
    private val fetcher: ModelFetchFn = ModelFetcher,
) {
    private val _status = MutableStateFlow<Map<String, AsrModelStatus>>(emptyMap())
    val status: StateFlow<Map<String, AsrModelStatus>> = _status.asStateFlow()

    private val locks = ConcurrentHashMap<String, Mutex>()

    fun isReady(lang: String): Boolean {
        val dir = modelDir(lang)
        return dir.resolve(MODEL_FILE).isFile && dir.resolve(TOKENS_FILE).isFile
    }

    /**
     * Ensures the ASR model for [lang] is present on disk, downloading it
     * if necessary. Idempotent and safe to call concurrently for the same
     * language. Returns the directory containing `model.int8.onnx` and
     * `tokens.txt`, or null on failure.
     */
    suspend fun ensureInstalled(lang: String): File? {
        return locks.computeIfAbsent(lang) { Mutex() }.withLock {
            if (isReady(lang)) {
                setStatus(lang, AsrModelStatus.Ready)
                return modelDir(lang)
            }

            val urls = modelUrls(lang)
            if (urls == null) {
                android.util.Log.e("AsrModelRepository", "No model source for language '$lang'")
                setStatus(lang, AsrModelStatus.NotInstalled)
                return null
            }

            val dir = modelDir(lang)
            dir.mkdirs()

            val modelFile = dir.resolve(MODEL_FILE)
            if (!modelFile.isFile) {
                setStatus(lang, AsrModelStatus.Downloading(0))
                val ok = fetcher.download(dest = modelFile, urls = urls.model, onProgress = { pct ->
                    setStatus(lang, AsrModelStatus.Downloading(pct))
                })
                if (!ok) {
                    android.util.Log.e("AsrModelRepository", "Failed to download ASR model for '$lang'")
                    setStatus(lang, AsrModelStatus.NotInstalled)
                    return null
                }
            }

            val tokensFile = dir.resolve(TOKENS_FILE)
            if (!tokensFile.isFile) {
                val ok = fetcher.download(dest = tokensFile, urls = urls.tokens, onProgress = {})
                if (!ok) {
                    android.util.Log.e("AsrModelRepository", "Failed to download tokens for '$lang'")
                    setStatus(lang, AsrModelStatus.NotInstalled)
                    return null
                }
            }

            setStatus(lang, AsrModelStatus.Ready)
            dir
        }
    }

    private fun modelDir(lang: String): File = modelsDir.resolve(lang)

    private fun setStatus(lang: String, status: AsrModelStatus) {
        _status.value = _status.value + (lang to status)
    }

    data class ModelUrls(val model: List<String>, val tokens: List<String>)

    private fun modelUrls(lang: String): ModelUrls? = when (lang) {
        in SUPPORTED_LANGUAGES -> ModelUrls(
            model = listOf("$REPO_LFS_BASE/$REPO_ASSETS_PATH/$lang/$MODEL_FILE"),
            tokens = listOf("$REPO_RAW_BASE/$REPO_ASSETS_PATH/$lang/$TOKENS_FILE")
        )
        "en" -> ModelUrls(
            model = listOf("$ENGLISH_MODEL_BASE/$MODEL_FILE"),
            tokens = listOf("$ENGLISH_MODEL_BASE/$TOKENS_FILE")
        )
        else -> null
    }

    companion object {
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"

        private const val REPO_LFS_BASE = "https://media.githubusercontent.com/media/Uktam-ai/uktam/main"
        private const val REPO_RAW_BASE = "https://raw.githubusercontent.com/Uktam-ai/uktam/main"
        private const val REPO_ASSETS_PATH = "asr_assets/src/main/assets"
        private const val ENGLISH_MODEL_BASE =
            "https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8/resolve/main"

        val SUPPORTED_LANGUAGES = setOf("hi", "kn", "ta", "te", "mr", "ml", "en")
    }
}
