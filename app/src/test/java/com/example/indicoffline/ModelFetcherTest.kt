package com.example.indicoffline

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val requests = AtomicInteger(0)

    private fun startServer(handler: (HttpExchange) -> Unit) {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    private fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String) = "http://localhost:${server.address.port}$path"

    @Test
    fun `downloads file and reports progress`() = runBlocking {
        val content = ByteArray(200_000) { (it % 256).toByte() }
        startServer { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        val dest = tmp.newFile("model.onnx")
        val progress = mutableListOf<Int>()

        val ok = ModelFetcher.download(
            dest = dest,
            urls = listOf(url("/model.onnx")),
            onProgress = { progress.add(it) },
            maxAttempts = 2,
            initialRetryDelayMs = 0
        )

        stopServer()
        assertTrue(ok)
        assertTrue(dest.readBytes().contentEquals(content))
        assertTrue(progress.last() == 100)
        assertTrue(progress.max() == 100)
    }

    @Test
    fun `resumes from partial temp file using range request`() = runBlocking {
        val content = ByteArray(200_000) { (it % 256).toByte() }
        val firstHalf = content.copyOfRange(0, 100_000)
        startServer { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").removeSuffix("-").toLong()
                val rest = content.copyOfRange(start.toInt(), content.size)
                exchange.responseHeaders.add("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                exchange.sendResponseHeaders(206, rest.size.toLong())
                exchange.responseBody.use { it.write(rest) }
            } else {
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
            }
        }
        val dir = tmp.newFolder("resume")
        val dest = File(dir, "model.onnx")
        File(dir, "model.onnx.tmp").writeBytes(firstHalf)

        val ok = ModelFetcher.download(
            dest = dest,
            urls = listOf(url("/model.onnx")),
            maxAttempts = 2,
            initialRetryDelayMs = 0
        )

        stopServer()
        assertTrue(ok)
        assertTrue(dest.readBytes().contentEquals(content))
    }

    @Test
    fun `falls back to next mirror url on failure`() = runBlocking {
        val content = "second-mirror-content".toByteArray()
        startServer { exchange ->
            if (exchange.requestURI.path == "/bad") {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
            }
        }
        val dest = tmp.newFile("model.onnx")

        val ok = ModelFetcher.download(
            dest = dest,
            urls = listOf(url("/bad"), url("/good")),
            maxAttempts = 2,
            initialRetryDelayMs = 0
        )

        stopServer()
        assertTrue(ok)
        assertTrue(dest.readBytes().contentEquals(content))
    }

    @Test
    fun `returns false when all attempts fail`() = runBlocking {
        startServer { exchange -> exchange.sendResponseHeaders(500, -1) }
        val dir = tmp.newFolder("allfail")
        val dest = File(dir, "model.onnx")

        val ok = ModelFetcher.download(
            dest = dest,
            urls = listOf(url("/model.onnx")),
            maxAttempts = 2,
            initialRetryDelayMs = 0
        )

        stopServer()
        assertFalse(ok)
        assertFalse(dest.exists())
    }

    @Test
    fun `dest file only appears after rename - no partial file on failure`() = runBlocking {
        startServer { exchange -> exchange.sendResponseHeaders(500, -1) }
        val dir = tmp.newFolder("atomic")
        val dest = File(dir, "model.onnx")

        val ok = ModelFetcher.download(
            dest = dest,
            urls = listOf(url("/model.onnx")),
            maxAttempts = 1,
            initialRetryDelayMs = 0
        )

        stopServer()
        assertFalse(ok)
        assertFalse(dest.exists())
        assertFalse(File(dir, "model.onnx.tmp").exists() && dest.exists())
    }
}
