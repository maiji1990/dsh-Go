package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import org.json.JSONObject

/**
 * Describes the Linux runtime for this APK flavor
 * (assets/runtime/manifest.json, written by runtime/build-runtime.sh).
 * Normal flavors bundle only the manifest; `rootfsUrl` is where they fetch
 * the archive. Offline flavors also bundle the archive and ignore it.
 */
/**
 * The DSH engine archive (manifest `engine`, see docs/design/engine-bundles.md):
 * the patched DSH npm tree, shipped apart from the rootfs so a new engine does
 * not mean a new rootfs. Only engines we built (matching [id] and [patchSet])
 * are accepted.
 */
data class EngineSpec(
    val id: String,
    val archive: String,
    val bytes: Long,
    /** Unpacked size of the engine tree (opt/dsh), for the free-space check. */
    val unpackedBytes: Long,
    val sha256: String?,
    /** Where to download the archive when the APK does not bundle it. */
    val url: String?,
    val patchSet: String,
    val dshVersion: String,
    /** This rootfs already contains the same tree (adopt it instead of downloading). */
    val inRootfs: Boolean,
)

data class RuntimeManifest(
    val runtimeVersion: String,
    /** Identity of the system layer alone; a new engine keeps it (see EngineSpec). */
    val rootfsVersion: String,
    val abi: String,
    val rootfsAsset: String,
    val rootfsBytes: Long,
    val rootfsSha256: String?,
    /** Where to download the archive when this APK does not bundle it (normal flavors). */
    val rootfsUrl: String?,
    val uncompressedBytes: Long,
    val dshVersion: String,
    val nodeVersion: String,
    /** proot argv template; placeholders are expanded by [LaunchSpec]. */
    val launchArgv: List<String>,
    val launchEnv: Map<String, String>,
    val engine: EngineSpec?,
    val raw: JSONObject,
) {
    companion object {
        const val ASSET_DIR = "runtime"

        fun load(context: Context): RuntimeManifest {
            val text = context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
            return parse(JSONObject(text))
        }

        fun parse(json: JSONObject): RuntimeManifest {
            val launch = json.optJSONObject("launch") ?: JSONObject()
            val argvJson = launch.optJSONArray("argv")
            val argv = if (argvJson != null) List(argvJson.length()) { argvJson.getString(it) } else emptyList()
            val envJson = launch.optJSONObject("env") ?: JSONObject()
            val env = envJson.keys().asSequence().associateWith { envJson.getString(it) }
            val engineJson = json.optJSONObject("engine")
            val engine = engineJson?.let {
                val id = it.optString("id")
                val patchSet = it.optString("patchSet")
                val archive = it.optString("archive")
                if (id.isEmpty() || patchSet.isEmpty() || archive.isEmpty()) null else EngineSpec(
                    id = id,
                    archive = archive,
                    bytes = it.optLong("archiveBytes", -1L),
                    unpackedBytes = it.optLong("uncompressedBytes", -1L),
                    sha256 = it.optString("archiveSha256").ifEmpty { null },
                    url = it.optString("url").ifEmpty { null },
                    patchSet = patchSet,
                    dshVersion = it.optString("dshVersion"),
                    inRootfs = it.optBoolean("rootfsIncludesDsh", false),
                )
            }
            return RuntimeManifest(
                runtimeVersion = json.getString("runtimeVersion"),
                rootfsVersion = json.optString("rootfsVersion").ifEmpty { json.getString("runtimeVersion") },
                abi = json.optString("abi"),
                rootfsAsset = json.getString("rootfs"),
                rootfsBytes = json.optLong("rootfsBytes", -1L),
                rootfsSha256 = json.optString("rootfsSha256").ifEmpty { null },
                rootfsUrl = json.optString("rootfsUrl").ifEmpty { null },
                uncompressedBytes = json.optLong("uncompressedBytes", -1L),
                dshVersion = json.optString("dshVersion"),
                nodeVersion = json.optString("nodeVersion"),
                launchArgv = argv,
                launchEnv = env,
                engine = engine,
                raw = json,
            )
        }
    }
}
