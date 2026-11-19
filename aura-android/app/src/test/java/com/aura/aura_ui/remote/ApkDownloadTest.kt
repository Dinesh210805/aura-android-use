package com.aura.aura_ui.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class ApkDownloadTest {

    @get:Rule val tmp = TemporaryFolder()

    private val client = OkHttpClient()

    private fun withServer(response: MockResponse, block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try { block(server) } finally { server.close() }
    }

    @Test fun `200 writes the file and reaches 100`() = withServer(
        MockResponse.Builder().body(Buffer().write(ByteArray(300_000) { 7 })).build(),
    ) { server ->
        val dest = tmp.root.resolve("update/aura-update.apk")
        val seen = mutableListOf<Int?>()
        val ok = runBlocking { downloadApk(client, server.url("/a.apk").toString(), dest) { seen += it } }
        assertTrue(ok)
        assertEquals(300_000L, dest.length())
        assertEquals(0, seen.first())
        assertEquals(100, seen.last())
    }

    @Test fun `404 returns false and leaves no file`() = withServer(MockResponse.Builder().code(404).build()) { server ->
        val dest = tmp.root.resolve("aura-update.apk")
        val ok = runBlocking { downloadApk(client, server.url("/a.apk").toString(), dest) {} }
        assertFalse(ok)
        assertFalse(dest.exists())
    }

    @Test fun `cancel deletes the partial file`() = withServer(
        MockResponse.Builder()
            .body(Buffer().write(ByteArray(2_000_000)))
            .throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS)
            .build(),
    ) { server ->
        val dest = tmp.root.resolve("aura-update.apk")
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job = async {
                downloadApk(client, server.url("/a.apk").toString(), dest) { if ((it ?: 0) > 0) started.complete(Unit) }
            }
            withTimeout(10_000) { started.await() }
            job.cancel()
            val cancelled = runCatching { job.await() }.exceptionOrNull() is CancellationException
            assertTrue("download must end as a cancellation", cancelled)
        }
        assertFalse(dest.exists())
    }

    /** The server sends 1 KB then stalls for 30 s; cancel must not wait for the 60 s read timeout. */
    @Test fun `cancel during a stalled read ends at once`() = withServer(
        MockResponse.Builder()
            .body(Buffer().write(ByteArray(100_000)))
            .throttleBody(1024, 30, TimeUnit.SECONDS)
            .build(),
    ) { server ->
        val dest = tmp.root.resolve("aura-update.apk")
        val slowClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job = async {
                downloadApk(slowClient, server.url("/a.apk").toString(), dest) { if ((it ?: 0) > 0) started.complete(Unit) }
            }
            withTimeout(10_000) { started.await() }
            val t0 = System.nanoTime()
            job.cancel()
            runCatching { job.await() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("cancel took $ms ms", ms < 3_000)
        }
        assertFalse(dest.exists())
    }
}
