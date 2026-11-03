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

    /** Older manifests have no separate system-layer identity. */
    @Test fun rootfsVersionFallsBackToTheRuntimeVersion() {
        assertEquals("r14-test", RuntimeManifest.parse(manifest()).rootfsVersion)
    }

    @Test fun readsASeparateRootfsVersion() {
        val parsed = RuntimeManifest.parse(manifest().put("rootfsVersion", "node24-ubuntu24-abc"))
        assertEquals("node24-ubuntu24-abc", parsed.rootfsVersion)
    }

    @Test fun readsTheEngineBundle() {
        val json = manifest().put(
            "engine",
            JSONObject(
                """
                {"id":"engine-0.2.1-rc.4-7cce7432f2de","archive":"engine-arm64-v8a.tar.xz",
                 "archiveBytes":789,"archiveSha256":"def","url":"https://example.com/engine.tar.xz",
                 "patchSet":"7cce","dshVersion":"0.2.1-rc.4","rootfsIncludesDsh":false}
                """.trimIndent(),
            ),
        )
        val parsed = RuntimeManifest.parse(json).engine!!
        assertEquals("engine-0.2.1-rc.4-7cce7432f2de", parsed.id)
        assertEquals("engine-arm64-v8a.tar.xz", parsed.archive)
        assertEquals(789, parsed.bytes)
        assertEquals("7cce", parsed.patchSet)
        assertEquals("0.2.1-rc.4", parsed.dshVersion)
        assertEquals(false, parsed.inRootfs)
    }

    /** A manifest that cannot name a vetted engine must not enable the engine path. */
    @Test fun ignoresAnEngineWithoutItsPatchSet() {
        val json = manifest().put("engine", JSONObject("""{"id":"engine-x","archive":"engine.tar.xz"}"""))
        assertNull(RuntimeManifest.parse(json).engine)
    }
}
