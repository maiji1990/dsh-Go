package io.github.ccch1mneyyy.dsh_app.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeManifestTest {
    private fun manifest(url: String? = null): JSONObject {
        val urlLine = if (url == null) "" else ",\n          \"rootfsUrl\": \"$url\""
        return JSONObject(
            """
            {
              "runtimeVersion": "r14-test",
              "abi": "arm64-v8a",
              "rootfs": "rootfs-arm64-v8a.tar.xz",
              "rootfsBytes": 123,
              "rootfsSha256": "abc",
              "uncompressedBytes": 456,
              "dshVersion": "0.2.1-rc.4",
              "nodeVersion": "24.22.1",
              "launch": {"argv": ["/bin/true"], "env": {"X": "Y"}}
              $urlLine
            }
            """.trimIndent(),
        )
    }

    @Test fun readsTheDownloadUrl() {
        val parsed = RuntimeManifest.parse(manifest("https://example.com/rootfs.tar.xz"))
        assertEquals("https://example.com/rootfs.tar.xz", parsed.rootfsUrl)
        assertEquals("rootfs-arm64-v8a.tar.xz", parsed.rootfsAsset)
        assertEquals(123, parsed.rootfsBytes)
        assertEquals(listOf("/bin/true"), parsed.launchArgv)
        assertEquals(mapOf("X" to "Y"), parsed.launchEnv)
    }

    @Test fun offlineManifestHasNoUrl() {
        assertNull(RuntimeManifest.parse(manifest()).rootfsUrl)
    }
}
