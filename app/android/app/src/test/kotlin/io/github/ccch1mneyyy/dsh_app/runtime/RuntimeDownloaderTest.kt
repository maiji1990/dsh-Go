package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RuntimeDownloaderTest {
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val requests = AtomicInteger()
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        dir = Files.createTempDirectory("runtime-downloader").toFile()
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "runtime-test-http") {
            while (!server.isClosed) {
                try {
                    server.accept().use { handle(it) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    /** One request per connection: `/resume` honours Range, `/full` ignores it. */
    private fun handle(socket: java.net.Socket) {
        requests.incrementAndGet()
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        val path = request.split(' ').getOrNull(1) ?: "/"
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val honour = path == "/resume"
        val start = if (honour && range != null) {
            range.removePrefix("bytes=").removeSuffix("-").toIntOrNull() ?: 0
        } else {
            0
        }
        val output = socket.getOutputStream()
        if (start > 0) {
            output.write(
                ("HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Range: bytes=$start-${content.size - 1}/${content.size}\r\n" +
                    "Content-Length: ${content.size - start}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            output.write(content, start, content.size - start)
        } else {
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Length: ${content.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            output.write(content)
        }
        output.flush()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    @Test fun downloadsAndVerifies() {
        val target = File(dir, "rootfs.tar.xz")
        var last = -1L
        RuntimeDownloader(url("/full"), target, sha, content.size.toLong()).fetch { _, total ->
            last = total
        }
        assertArrayEquals(content, target.readBytes())
        assertEquals(content.size.toLong(), last)
        assertEquals(1, requests.get())
    }

    @Test fun resumesAPartialFile() {
        val target = File(dir, "rootfs.tar.xz")
        val part = File(target.path + ".part")
        part.writeBytes(content.copyOfRange(0, 120_000))
        RuntimeDownloader(url("/resume"), target, sha, content.size.toLong()).fetch { _, _ -> }
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())
    }

    @Test fun restartsWhenTheServerIgnoresRange() {
        val target = File(dir, "rootfs.tar.xz")
        File(target.path + ".part").writeBytes(content.copyOfRange(0, 120_000))
        RuntimeDownloader(url("/full"), target, sha, content.size.toLong()).fetch { _, _ -> }
        assertArrayEquals(content, target.readBytes())
    }

    @Test fun rejectsAWrongChecksum() {
        val target = File(dir, "rootfs.tar.xz")
        try {
            RuntimeDownloader(url("/full"), target, "00".repeat(32), content.size.toLong()).fetch { _, _ -> }
            throw AssertionError("expected a checksum failure")
        } catch (_: RuntimeChecksumException) {
        }
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test fun reusesACompleteVerifiedFile() {
        val target = File(dir, "rootfs.tar.xz")
        target.writeBytes(content)
        RuntimeDownloader(url("/full"), target, sha, content.size.toLong()).fetch { _, _ -> }
        assertEquals(0, requests.get())
        assertTrue(target.isFile)
    }
}
