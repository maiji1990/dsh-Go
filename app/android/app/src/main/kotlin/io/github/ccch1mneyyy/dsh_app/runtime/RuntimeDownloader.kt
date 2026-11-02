package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The archive arrived but its SHA-256 is not the manifest's (the file is discarded). */
internal class RuntimeChecksumException(message: String) : IOException(message)

/**
 * Downloads a runtime archive with HTTP Range resume. The partial file is
 * `<target>.part`, so a stopped download continues from where it was; a
 * server that ignores Range (answers 200) makes it start over. Only a complete,
 * size- and SHA-256-checked archive is renamed to [target].
 */
internal class RuntimeDownloader(
    private val url: String,
    private val target: File,
    private val expectedSha256: String?,
    private val expectedBytes: Long,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun fetch(onProgress: (Long, Long) -> Unit): File {
        target.parentFile?.mkdirs()
        if (target.isFile && matches(target)) return target

        val part = File(target.path + ".part")
        if (part.isFile && expectedBytes > 0 && part.length() == expectedBytes) {
            if (matches(part)) return promote(part)
            part.delete()
        }
        var existing = if (part.isFile) part.length() else 0L
        if (expectedBytes > 0 && existing > expectedBytes) {
            part.delete()
            existing = 0
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "dsh-go")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            val resumed = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                416 -> {
                    if (expectedBytes > 0 && part.length() == expectedBytes) {
                        return verifyAndPromote(part)
                    }
                    part.delete()
                    throw IOException("runtime download range not satisfiable")
                }
                else -> throw IOException("runtime download failed (HTTP $code)")
            }
            if (!resumed && existing > 0) {
                part.delete()
                existing = 0
            }
            val length = connection.contentLengthLong
            val total = when {
                expectedBytes > 0 -> expectedBytes
                resumed && length > 0 -> existing + length
                length > 0 -> length
                else -> -1L
            }
            onProgress(existing, total)
            connection.inputStream.use { input ->
                FileOutputStream(part, resumed && existing > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = existing
                    var reported = done
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - reported >= 256 * 1024 || (total > 0 && done >= total)) {
                            onProgress(done, total)
                            reported = done
                        }
                    }
                    onProgress(done, total)
                }
            }
        } finally {
            connection.disconnect()
        }
        if (expectedBytes > 0 && part.length() != expectedBytes) {
            throw IOException("runtime download incomplete (${part.length()} of $expectedBytes bytes)")
        }
        return verifyAndPromote(part)
    }

    private fun verifyAndPromote(part: File): File {
        if (expectedSha256 != null && !sha256(part).equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            throw RuntimeChecksumException("runtime archive SHA-256 mismatch")
        }
        return promote(part)
    }

    private fun promote(part: File): File {
        if (target.isFile) target.delete()
        if (!part.renameTo(target)) throw IOException("could not finalize runtime download")
        return target
    }

    private fun matches(file: File): Boolean =
        (expectedBytes <= 0 || file.length() == expectedBytes) &&
            (expectedSha256 == null || sha256(file).equals(expectedSha256, ignoreCase = true))

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
