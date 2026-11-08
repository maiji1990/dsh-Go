package io.github.ccch1mneyyy.dsh_app.phone

import java.util.UUID

/** One process-wide UI coordinator; all native work still enters PhoneCore's existing worker/gate. */
class UiMcpFacade(private val core: PhoneCore, private val now: () -> Long = System::currentTimeMillis) {
    private class Failure(val code: String, override val message: String) : RuntimeException(message)
    private data class Cached(val id: String, val owner: String, val screen: ScreenSnapshot,
                              val observation: UiObservation, val refs: Map<String, UiObservedElement>)
    private val lock = Any()
    private val sessions = LinkedHashMap<String, Long>()
    private var latest: Cached? = null
    private var sequence = 0L
    private var generation = UUID.randomUUID().toString()

    fun reset() = synchronized(lock) {
        generation = UUID.randomUUID().toString(); latest = null; sessions.clear(); sequence = 0
    }

    fun call(name: String, args: Map<String, Any?>): ToolOutput {
        val started = now()
        return try {
            validate(name, args)
            if (name == "ui_wait_for") waitFor(args, started)
            else synchronized(lock) { dispatch(name, args) }
        } catch (e: Failure) {
            core.log.add(name, null, e.code, "denied")
            failure(e.code, e.message)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failure("OPERATION_TIMEOUT", "UI request interrupted; do not retry writes automatically")
        } catch (_: Exception) {
            synchronized(lock) { latest = null }
            core.log.add(name, null, "OBSERVATION_INCOMPLETE", "failed")
            failure("OBSERVATION_INCOMPLETE", "UI operation failed; observe again and do not automatically retry writes")
        }
    }

    private fun validate(name: String, args: Map<String, Any?>) {
        val tool = UiToolCatalog.tools.firstOrNull { it["name"] == name }
            ?: fail("ACTION_UNSUPPORTED", "Unknown unified UI tool")
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        if (args.keys.any { !properties.containsKey(it) }) fail("INVALID_ARGUMENT", "Unknown UI argument")
        for (key in schema["required"] as List<*>)
            if (!args.containsKey(key)) fail("INVALID_ARGUMENT", "Missing argument: $key")
        for ((key, value) in args) {
            val type = (properties[key] as Map<*, *>)["type"]
            if (value == null && !(schema["required"] as List<*>).contains(key)) continue
            val valid = when (type) {
                "string" -> value is String
                "boolean" -> value is Boolean
                "integer" -> value is Int || value is Long
                else -> false
            }
            if (!valid) fail("INVALID_ARGUMENT", "Invalid argument type: $key")
        }
        if (str(args, "deviceId") !in listOf(null, DEVICE)) fail("DEVICE_OFFLINE", "Unknown device")
        str(args, "role")?.let { if (it.lowercase() !in ROLES) fail("INVALID_ARGUMENT", "Unknown role") }
        str(args, "expectedRole")?.let { if (it.lowercase() !in ROLES) fail("INVALID_ARGUMENT", "Unknown expected role") }
    }

    private fun session(args: Map<String, Any?>): String {
        val id = str(args, "sessionId") ?: "default"
        if (id.isBlank() || id.length > 128) fail("INVALID_ARGUMENT", "Invalid session ID")
        val created = sessions[id]
        if (created == null) {
            if (sessions.size >= 128) fail("INVALID_ARGUMENT", "UI session limit reached; restart the bridge")
            sessions[id] = now()
        } else if (now() - created >= SESSION_TTL) {
            latest?.takeIf { it.owner == id }?.let { latest = null }
            fail("INVALID_ARGUMENT", "Session expired; use a new session ID")
        }
        return id
    }

    private fun target(args: Map<String, Any?>): UiTargetState {
        val state = core.uiInspect() ?: fail("POLICY_DENIED", "Cannot verify an authorized foreground target")
        if (state.secure || !core.uiCanRead(state.packageName)) fail("POLICY_DENIED", "Foreground target denied")
        if (str(args, "applicationId")?.let { it != state.packageName } == true ||
            str(args, "windowId")?.let { it != state.windowId } == true)
            fail("POLICY_DENIED", "Actual foreground target differs from the requested target")
        return state
    }

    private fun capture(args: Map<String, Any?>, owner: String, image: Boolean = false): Cached {
        val maxNodes = number(args, "maxNodes", 200, 1, 2000)
        latest = null
        val expected = target(args)
        val out = core.callUi(PhoneToolCatalog.SCREEN, mapOf("detail" to true, "image" to image), UiActionGuard(expected))
        checkResult(out)
        val screen = out.snapshot ?: fail("OBSERVATION_INCOMPLETE", "Native snapshot is missing")
        val observation = screen.ui ?: fail("SECURE_CONTENT", "Structured observation is unavailable or protected")
        if (screen.secure || observation.target.secure ||
            observation.packages.isEmpty() || observation.packages.any { !core.uiCanRead(it) })
            fail("SECURE_CONTENT", "Observation includes an unauthorized or protected window")
        if (image && observation.elements.any { it.sensitive })
            fail("SECURE_CONTENT", "Screenshot contains a sensitive field in a source window")
        val after = target(args)
        if (after.packageName != expected.packageName || after.windowId != expected.windowId ||
            after.screenId != screen.id || observation.target.packageName != expected.packageName ||
            observation.target.windowId != expected.windowId)
            fail("STALE_ELEMENT", "Target changed during capture; content discarded")
        val scoped = observation.elements.filter { it.packageName == expected.packageName && it.windowId == expected.windowId }
        val elements = scoped.take(maxNodes)
        val bounded = observation.copy(elements = elements, truncated = observation.truncated || elements.size < scoped.size)
        val cached = Cached("ui-${generation.take(8)}-${++sequence}", owner, screen, bounded,
            elements.mapIndexed { i, e -> "e${i + 1}" to e }.toMap())
        latest = cached
        core.log.add("ui_snapshot", expected.packageName, "snapshot", "ok")
        return cached
    }

    private fun current(args: Map<String, Any?>, owner: String): Cached {
        val cached = latest ?: fail("STALE_ELEMENT", "Snapshot expired; observe again")
        if (str(args, "snapshotId") != cached.id || cached.owner != owner)
            fail("STALE_ELEMENT", "Snapshot does not belong to this session")
        val state = target(args)
        if (state.screenId != cached.screen.id || state.packageName != cached.observation.target.packageName ||
            state.windowId != cached.observation.target.windowId) {
            latest = null
            fail("STALE_ELEMENT", "Native snapshot or target changed")
        }
        return cached
    }

    private fun snapshotData(c: Cached): Map<String, Any?> = linkedMapOf(
        "snapshotId" to c.id, "provider" to PROVIDER, "revision" to sequence, "target" to c.observation.target.identity(),
        "truncated" to c.observation.truncated, "elements" to c.refs.map { (ref, el) -> el.toMap(ref) },
    )

    private fun dispatch(name: String, args: Map<String, Any?>): ToolOutput {
        val owner = session(args)
        if (!core.uiAvailable()) fail("PROVIDER_UNAVAILABLE", "Phone control is unavailable, disabled or locked")
        return when (name) {
            "ui_list_devices" -> success(mapOf("devices" to listOf(mapOf("deviceId" to DEVICE,
                "name" to "Android local", "platform" to "android", "transport" to "local-a11y", "providerId" to PROVIDER, "online" to true))))
            "ui_list_targets" -> {
                val state = target(args)
                success(mapOf("deviceId" to DEVICE, "targets" to listOf(mapOf("applicationId" to state.packageName,
                    "windowId" to state.windowId, "isForeground" to true)), "nativeSnapshotId" to state.screenId))
            }
            "ui_get_capabilities" -> success(mapOf("providerId" to PROVIDER, "platform" to "android",
                "supportedActions" to listOf("click", "set_text", "press_key", "scroll", "screenshot"),
                "supportsSemanticSnapshot" to true, "supportsScreenshot" to true))
            "ui_snapshot" -> {
                if (str(args, "mode") !in listOf(null, "compact", "normal", "full")) fail("INVALID_ARGUMENT", "Invalid snapshot mode")
                success(snapshotData(capture(args, owner)))
            }
            "ui_find" -> {
                val c = if (str(args, "snapshotId") == null) capture(args, owner) else current(args, owner)
                val matches = c.refs.filterValues { matches(it, args) }.map { (ref, e) ->
                    e.toMap(ref) + mapOf("snapshotId" to c.id) }
                if (matches.isEmpty()) fail("ELEMENT_NOT_FOUND", "No matching element")
                success(mapOf("matches" to matches, "snapshotId" to c.id, "total" to matches.size))
            }
            "ui_get_element" -> {
                val c = current(args, owner)
                val ref = str(args, "refId")!!
                val e = c.refs[ref] ?: fail("STALE_ELEMENT", "Unknown element ref")
                success(e.toMap(ref) + mapOf("snapshotId" to c.id))
            }
            "ui_click", "ui_set_text" -> elementAction(name, args, owner)
            "ui_press_key", "ui_scroll" -> globalAction(name, args, owner)
            "ui_screenshot" -> {
                val c = capture(args, owner, image = true)
                if (c.observation.truncated || c.observation.elements.any { it.sensitive } || c.screen.image == null)
                    fail("SECURE_CONTENT", "Screenshot unavailable, incomplete or contains sensitive fields")
                success(mapOf("phase" to "observed", "mimeType" to c.screen.imageMime), c.screen)
            }
            else -> fail("ACTION_UNSUPPORTED", "Unknown unified UI tool")
        }
    }

    private fun elementAction(name: String, args: Map<String, Any?>, owner: String): ToolOutput {
        val c = current(args, owner)
        val el = c.refs[str(args, "refId")] ?: fail("STALE_ELEMENT", "Unknown element ref")
        if (str(args, "expectedRole")?.lowercase()?.let { it != el.role } == true ||
            str(args, "expectedName")?.let { it != el.name } == true) fail("STALE_ELEMENT", "Element does not match expectation")
        val input = name == "ui_set_text"
        if (!el.enabled || !el.visible || el.sensitive ||
            (if (input) "set_text" else "click") !in el.actions || input && el.role != "textbox")
            fail("ACTION_UNSUPPORTED", "Element cannot perform the requested action")
        val native = linkedMapOf<String, Any?>("screen" to c.screen.id, "id" to el.id)
        if (input) {
            val text = str(args, "text")!!
            if (text.length > 2000) fail("INVALID_ARGUMENT", "Input exceeds 2000 characters")
            native["text"] = text
        }
        val expected = c.observation.target
        latest = null // Invalidate before dispatch, including failed/uncertain writes.
        val out = core.callUi(if (input) PhoneToolCatalog.TYPE else PhoneToolCatalog.TAP, native,
            UiActionGuard(expected, c.screen.id))
        checkResult(out)
        return success(mapOf("phase" to if (out.snapshot?.ui != null) "observed" else "dispatched",
            "hint" to "Observe again before continuing"))
    }

    private fun globalAction(name: String, args: Map<String, Any?>, owner: String): ToolOutput {
        val key = str(args, "key")
        val direction = str(args, "direction") ?: "down"
        val amount = if (name == "ui_scroll") number(args, "amount", 3, 1, 10) else 1
        if (name == "ui_press_key" && key !in listOf("back", "home", "recents", "notifications"))
            fail("ACTION_UNSUPPORTED", "Unsupported Android key")
        if (name == "ui_scroll" && direction !in listOf("up", "down", "left", "right"))
            fail("INVALID_ARGUMENT", "Invalid direction")
        if (str(args, "refId") != null && (str(args, "snapshotId") == null || amount != 1))
            fail("INVALID_ARGUMENT", "Explicit scroll ref needs a snapshot and amount=1")
        var c = if (str(args, "snapshotId") == null) capture(args, owner) else current(args, owner)
        for (step in 0 until amount) {
            val native = linkedMapOf<String, Any?>("screen" to c.screen.id)
            if (name == "ui_press_key") native["key"] = key else {
                native["direction"] = direction
                val ref = str(args, "refId")
                val element = if (ref != null) c.refs[ref] ?: fail("STALE_ELEMENT", "Unknown scroll ref")
                    else c.refs.values.filter { "scroll" in it.actions }
                        .maxByOrNull { it.width.toLong().coerceAtLeast(0) * it.height.toLong().coerceAtLeast(0) }
                        ?: fail("ACTION_UNSUPPORTED", "No scrollable element in the selected window")
                if ("scroll" !in element.actions) fail("ACTION_UNSUPPORTED", "Element is not scrollable")
                native["id"] = element.id
            }
            latest = null
            val out = core.callUi(if (name == "ui_press_key") PhoneToolCatalog.KEY else PhoneToolCatalog.SCROLL,
                native, UiActionGuard(c.observation.target, c.screen.id))
            checkResult(out)
            if (step + 1 < amount) c = capture(args, owner)
        }
        latest = null
        return success(mapOf("phase" to "dispatched", "hint" to "Observe again; do not automatically retry writes"))
    }

    private fun waitFor(args: Map<String, Any?>, started: Long): ToolOutput {
        val timeout = number(args, "timeoutMs", 10_000, 100, 120_000)
        do {
            val result = synchronized(lock) {
                val owner = session(args)
                val c = capture(args, owner)
                val found = c.refs.filterValues { matches(it, args) }
                val absent = args["absent"] == true
                if (if (absent) found.isEmpty() && !c.observation.truncated else found.isNotEmpty())
                    success(mapOf("satisfied" to true, "snapshotId" to c.id,
                        "matchedElements" to found.map { (ref, e) -> e.toMap(ref) })) else null
            }
            if (result != null) return result
            if (now() - started >= timeout) break
            Thread.sleep(minOf(400, (timeout - (now() - started)).coerceAtLeast(1)))
        } while (now() - started < timeout)
        fail("OPERATION_TIMEOUT", "Wait timed out")
    }

    private fun matches(e: UiObservedElement, args: Map<String, Any?>): Boolean =
        (str(args, "role") == null || str(args, "role")!!.lowercase() == e.role) &&
        (str(args, "nativeId") == null || str(args, "nativeId") == e.nativeId) &&
        (str(args, "name") == null || !e.sensitive && e.name.contains(str(args, "name")!!, ignoreCase = true))

    private fun checkResult(out: ToolOutput) {
        if (out.isError) fail(out.errorCode ?: "OBSERVATION_INCOMPLETE", "Native operation refused or incomplete; inspect the phone confirmation/stop state")
    }
    private fun success(data: Map<String, Any?>, image: ScreenSnapshot? = null): ToolOutput {
        val result = mapOf("success" to true, "data" to (data + mapOf("bridge" to mapOf("version" to 1, "generation" to generation))))
        return ToolOutput(Json.write(result), image = image?.image, imageMime = image?.imageMime, structuredContent = result)
    }
    private fun failure(code: String, message: String): ToolOutput {
        val result = mapOf("success" to false, "error" to mapOf("code" to code, "message" to message))
        return ToolOutput(Json.write(result), isError = true, structuredContent = result)
    }
    private fun fail(code: String, message: String): Nothing = throw Failure(code, message)
    private fun str(args: Map<String, Any?>, key: String): String? = args[key] as? String
    private fun number(args: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
        val n = (args[key] as? Number)?.toLong() ?: default.toLong()
        if (n < min || n > max) fail("INVALID_ARGUMENT", "$key out of range $min..$max")
        return n.toInt()
    }
    companion object {
        const val DEVICE = "android-local"
        const val PROVIDER = "dshgo-ui"
        private const val SESSION_TTL = 2 * 60 * 60 * 1000L
        private val ROLES = setOf("unknown", "window", "group", "button", "textbox", "text", "checkbox", "radiobutton", "switch", "list", "listitem", "tab", "image", "link")
    }
}
