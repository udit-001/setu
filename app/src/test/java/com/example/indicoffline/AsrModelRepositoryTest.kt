package com.example.indicoffline

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrModelRepositoryTest {

    private fun newRepo(
        fetcher: ModelFetchFn
    ): Pair<AsrModelRepository, File> {
        val modelsDir = Files.createTempDirectory("asr-models").toFile()
        return AsrModelRepository(modelsDir = modelsDir, fetcher = fetcher) to modelsDir
    }

    private fun fakeFetcher(
        failModel: Boolean = false,
        failTokens: Boolean = false,
        onCall: (File) -> Unit = {}
    ) = object : ModelFetchFn {
        override suspend fun download(
            dest: File,
            urls: List<String>,
            onProgress: suspend (Int) -> Unit,
            maxAttempts: Int,
            initialRetryDelayMs: Long
        ): Boolean {
            onCall(dest)
            when {
                failModel && dest.name == "model.int8.onnx" -> return false
                failTokens && dest.name == "tokens.txt" -> return false
            }
            dest.writeBytes(byteArrayOf(1, 2, 3))
            onProgress(50)
            onProgress(100)
            return true
        }
    }

    @Test
    fun `ensureInstalled downloads and reports Ready`() = runBlocking {
        val (repo, dir) = newRepo(fakeFetcher())
        val progress = mutableListOf<Int>()

        val modelDir = repo.ensureInstalled("hi")

        assertNotNull(modelDir)
        assertTrue(modelDir!!.resolve("model.int8.onnx").isFile)
        assertTrue(modelDir.resolve("tokens.txt").isFile)
        assertEquals(AsrModelStatus.Ready, repo.status.value["hi"])
    }

    @Test
    fun `ensureInstalled is idempotent - ready language does not download again`() = runBlocking {
        var downloads = 0
        val (repo, _) = newRepo(fakeFetcher(onCall = { downloads++ }))

        repo.ensureInstalled("hi")
        repo.ensureInstalled("hi")

        assertEquals(2, downloads) // model + tokens, once each
    }

    @Test
    fun `returns null and NotInstalled when model download fails`() = runBlocking {
        val (repo, dir) = newRepo(fakeFetcher(failModel = true))

        val result = repo.ensureInstalled("kn")

        assertNull(result)
        assertEquals(AsrModelStatus.NotInstalled, repo.status.value["kn"])
        assertFalse(repo.isReady("kn"))
    }

    @Test
    fun `returns null and NotInstalled when tokens download fails`() = runBlocking {
        val (repo, dir) = newRepo(fakeFetcher(failTokens = true))

        val result = repo.ensureInstalled("kn")

        assertNull(result)
        assertEquals(AsrModelStatus.NotInstalled, repo.status.value["kn"])
    }

    @Test
    fun `reports Downloading status during model download`() = runBlocking {
        val modelsDir = Files.createTempDirectory("asr-models").toFile()
        var statusDuringDownload: AsrModelStatus? = null
        var repoRef: AsrModelRepository? = null
        val repo = AsrModelRepository(
            modelsDir = modelsDir,
            fetcher = object : ModelFetchFn {
                override suspend fun download(
                    dest: File,
                    urls: List<String>,
                    onProgress: suspend (Int) -> Unit,
                    maxAttempts: Int,
                    initialRetryDelayMs: Long
                ): Boolean {
                    onProgress(25)
                    statusDuringDownload = repoRef?.status?.value?.get("ta")
                    dest.writeBytes(byteArrayOf(1))
                    return true
                }
            }
        )
        repoRef = repo

        repo.ensureInstalled("ta")

        assertEquals(AsrModelStatus.Downloading(25), statusDuringDownload)
        assertEquals(AsrModelStatus.Ready, repo.status.value["ta"])
    }

    @Test
    fun `already-installed language is Ready without any download`() = runBlocking {
        val (repo, dir) = newRepo(fakeFetcher(onCall = {
            throw AssertionError("fetcher should not be called for an installed language")
        }))
        dir.resolve("hi").mkdirs()
        File(dir, "hi/model.int8.onnx").writeBytes(byteArrayOf(1))
        File(dir, "hi/tokens.txt").writeBytes(byteArrayOf(1))

        val modelDir = repo.ensureInstalled("hi")

        assertNotNull(modelDir)
        assertEquals(AsrModelStatus.Ready, repo.status.value["hi"])
    }

    @Test
    fun `unknown language returns null without download`() = runBlocking {
        var called = false
        val (repo, _) = newRepo(fakeFetcher(onCall = { called = true }))

        assertNull(repo.ensureInstalled("zz"))
        assertFalse(called)
        assertTrue(AsrModelRepository.SUPPORTED_LANGUAGES.contains("en"))
    }
}
