package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.content.res.AssetManager
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import org.json.JSONObject
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Installs the DSH engine tree apart from the rootfs (manifest `engine`, see
 * docs/design/engine-bundles.md): a new engine downloads ~34 MB instead of a
 * whole rootfs.
 *
 * Layout under filesDir/runtime:
 *   engines/<id>/           one installed engine (`opt/dsh` + `engine.json`)
 *   engines/<id>.staging/   in-progress extraction, renamed when complete
 *   engines/current         id of the engine to launch
 *
 * Only an engine whose `id`, `patchSet` and `dshVersion` match the manifest is
 * accepted, so an arbitrary npm tree can never be substituted for a vetted one.
 * A runtime that still ships DSH inside its rootfs is adopted (moved, not
 * downloaded) the first time a split runtime is installed.
 */
class EngineInstaller(
    private val context: Context,
    private val manifest: RuntimeManifest,
    private val onLog: (String) -> Unit,
    private val sync: () -> Unit = ::syncFilesystems,
) {
    // Getters, not initializers: AGP's lint (FIR) crashes on properties derived
    // from constructor properties here ("Unexpected owner function: null").
    private val baseDir: File get() = File(context.filesDir, "runtime")
    private val enginesDir: File get() = File(baseDir, "engines")
    private val downloadDir: File get() = File(baseDir, "engine-download")
    private val currentFile: File get() = File(enginesDir, "current")
    private val spec: EngineSpec? get() = manifest.engine

    private fun engineDir(id: String) = File(enginesDir, id)
    private fun stagingDir(id: String) = File(enginesDir, "$id.staging")

    /** Nothing to install, or the manifest's engine is already in place and active. */
    fun isReady(): Boolean {
        val s = spec ?: return true
        return activeId() == s.id && isInstalled(engineDir(s.id), s)
    }

    private fun activeId(): String? =
        currentFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    fun needed(): Boolean = !isReady()

    /**
     * Installs (or adopts, or unpacks a bundled copy of) the manifest's engine.
     * [onProgress] gets (done, total) for the current phase; callers may offset
     * it to show one overall bar.
     */
    fun install(onProgress: (Long, Long) -> Unit) {
        val s = spec ?: return
        val target = engineDir(s.id)
        enginesDir.mkdirs()
        downloadDir.listFiles()?.forEach { if (it.name != s.id) it.deleteRecursivelySafely() }
        if (isInstalled(target, s)) {
            setCurrent(s.id)
            return
        }
        stagingDir(s.id).deleteRecursivelySafely()

        // A runtime that shipped DSH in its rootfs already has exactly this tree.
        if (adoptFromRootfs(s)) {
            setCurrent(s.id)
            return
        }

        val staging = stagingDir(s.id).apply { mkdirs() }
        var downloaded: File? = null
        try {
            val assetPath = "${RuntimeManifest.ASSET_DIR}/${s.archive}"
            if (hasAsset(s.archive)) {
                onLog("[dsh-app] installing bundled engine ${s.id}")
                extractAsset(assetPath, s, staging, onProgress)
            } else {
                val url = s.url
                    ?: throw IllegalStateException(AppStrings.get(context, R.string.service_runtime_no_source))
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    throw IllegalStateException(AppStrings.get(context, R.string.service_runtime_download_failed))
                }
                onLog("[dsh-app] downloading engine ${s.id}")
                val archive = download(url, s, onProgress)
                downloaded = archive
                archive.inputStream().use { raw ->
                    TarExtractor(staging, { read -> onProgress(read, archive.length()) })
                        .extract(raw, archive.name)
                }
            }
            verifyStaged(staging, s)
            activate(staging, target, s)
            // Only a successful install keeps the download; a failed extraction
            // is retried from the verified archive instead of downloading again.
            downloaded?.deleteRecursivelySafely()
        } catch (e: Throwable) {
            staging.deleteRecursivelySafely()
            throw e
        }
    }

    private fun adoptFromRootfs(s: EngineSpec): Boolean {
        // Only a rootfs built from this same engine recipe may be adopted; an
        // older tree with the same DSH version could be missing newer patches.
        if (!s.inRootfs) return false
        val rootfs = File(baseDir, "rootfs")
        val source = File(rootfs, "opt/dsh")
        if (!File(source, "node_modules/@deepseek-ai/dsh/lib/bin.js").isFile) return false
        val version = runCatching {
            JSONObject(File(source, "node_modules/@deepseek-ai/dsh/package.json").readText()).optString("version")
        }.getOrNull()
        if (version != s.dshVersion) {
            onLog("[dsh-app] rootfs DSH $version does not match engine ${s.dshVersion}; not adopting")
            return false
        }
        val staging = stagingDir(s.id).apply { mkdirs() }
        val moved = File(staging, "opt/dsh")
        moved.parentFile?.mkdirs()
        if (!source.renameTo(moved)) {
            staging.deleteRecursivelySafely()
            onLog("[dsh-app] could not adopt the rootfs engine; will download it")
            return false
        }
        // The launcher binds the engine over /opt/dsh; keep the mount point.
        File(rootfs, "opt/dsh").mkdirs()
        writeMetadata(staging, s)
        activate(staging, engineDir(s.id), s)
        onLog("[dsh-app] adopted the rootfs engine ${s.id}")
        return true
    }

    private fun download(url: String, s: EngineSpec, onProgress: (Long, Long) -> Unit): File {
        val target = File(File(downloadDir, s.id), s.archive)
        return try {
            RuntimeDownloader(url, target, s.sha256, s.bytes).fetch { done, total ->
                onProgress(done, if (total > 0) total else s.bytes)
            }
        } catch (e: RuntimeChecksumException) {
            throw IllegalStateException(AppStrings.get(context, R.string.service_package_checksum_failed), e)
        } catch (e: java.io.IOException) {
            throw IllegalStateException(AppStrings.get(context, R.string.service_runtime_download_failed), e)
        }
    }

    private fun extractAsset(
        assetPath: String,
        s: EngineSpec,
        staging: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val total = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrDefault(s.bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { raw ->
            DigestInputStream(raw, digest).use { verified ->
                TarExtractor(staging, { read -> onProgress(read, total) }).extract(verified, s.archive)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (s.sha256 != null && !s.sha256.equals(actual, ignoreCase = true)) {
            throw IllegalStateException(AppStrings.get(context, R.string.service_package_checksum_failed))
        }
    }

    private fun verifyStaged(staging: File, s: EngineSpec) {
        if (!isInstalled(staging, s)) {
            onLog("[dsh-app] engine package ${s.id} failed its manifest check")
            throw IllegalStateException(AppStrings.get(context, R.string.service_package_checksum_failed))
        }
    }

    private fun activate(staging: File, target: File, s: EngineSpec) {
        target.deleteRecursivelySafely()
        if (!staging.renameTo(target)) throw IllegalStateException("engine: could not activate ${s.id}")
        syncDirectory(enginesDir)
        setCurrent(s.id)
        prune(s.id)
    }

    private fun setCurrent(id: String) {
        currentFile.writeText(id)
        sync()
        syncDirectory(enginesDir)
    }

    /** Keep the active engine and the one before it (rollback); drop older and leftover partials. */
    private fun prune(keep: String) {
        val dirs = enginesDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("engine-") && !it.name.endsWith(".staging") }
            ?: return
        dirs.filter { it.name != keep }
            .sortedByDescending { it.lastModified() }
            .drop(1)
            .forEach { it.deleteRecursivelySafely() }
        enginesDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".staging") }
            ?.forEach { it.deleteRecursivelySafely() }
    }

    private fun writeMetadata(dir: File, s: EngineSpec) {
        val meta = JSONObject()
            .put("schema", 1)
            .put("id", s.id)
            .put("abi", manifest.abi)
            .put("dshVersion", s.dshVersion)
            .put("nodeVersion", manifest.nodeVersion)
            .put("patchSet", s.patchSet)
            .put("root", "/opt/dsh")
        File(dir, "engine.json").writeText(meta.toString(2) + "\n")
    }

    private fun isInstalled(dir: File, s: EngineSpec): Boolean {
        if (!File(dir, "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile) return false
        val meta = runCatching { JSONObject(File(dir, "engine.json").readText()) }.getOrNull() ?: return false
        return meta.optString("id") == s.id &&
            meta.optString("patchSet") == s.patchSet &&
            meta.optString("dshVersion") == s.dshVersion
    }

    private fun hasAsset(name: String): Boolean =
        runCatching { context.assets.list(RuntimeManifest.ASSET_DIR)?.contains(name) == true }.getOrDefault(false)

    companion object {
        /** The engine directory the launcher should bind over /opt/dsh, if any. */
        fun activeDir(context: Context): File? {
            val engines = File(context.filesDir, "runtime/engines")
            val id = File(engines, "current").takeIf { it.isFile }?.readText()?.trim().orEmpty()
            if (id.isEmpty()) return null
            val dir = File(engines, id)
            return dir.takeIf { File(it, "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile }
        }
    }
}
