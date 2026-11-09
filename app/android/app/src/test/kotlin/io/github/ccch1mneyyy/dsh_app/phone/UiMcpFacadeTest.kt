package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Runs the facade through the real worker, policy, confirmation and action dispatch. */
class UiMcpFacadeTest {
    private val tasks = PhoneTasks()
    private var policies = PhonePolicies()
    private var locked = false
    private var asked = 0
    private var confirm: () -> Decision = { Decision.ALLOW_ONCE }
    private val main = Driver()
    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = policies
        override fun isLocked() = locked
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest): Decision { asked++; return confirm() }
            override fun handoff(reason: String, timeoutMs: Long): HandoffResult { return HandoffResult.STOP }
            override fun status(text: String?) {}
        }
        override fun driver(screen: PhoneScreen) = main
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun beforeHandoff(screen: PhoneScreen, owner: PhoneTasks.Owner): String? { return null }
        override fun sleep(ms: Long) {}
        override fun system() = object : SystemApi {
            override fun apps() = listOf(AppEntry("Example", PhoneAppIdentity("example.app", 10123)))
            override fun isInstalled(pkg: String) = true
            override fun prepareLaunch(app: AppEntry): Either<String, SystemLaunch> = error("unexpected launch")
            override fun prepareUrl(url: String): Either<String, SystemLaunch> = error("unexpected URL")
            override fun prepareIntent(spec: IntentSpec, selectedApp: AppEntry?): Either<String, SystemLaunch> = error("unexpected intent")
            override fun launch(plan: SystemLaunch, dispatch: PhoneTasks.SystemDispatch): SysResult = error("unexpected launch")
            override fun notify(title: String, text: String): SysResult = error("unexpected notification")
            override fun clipboardSet(text: String): SysResult = error("unexpected clipboard")
            override fun clipboardGet(): SysResult = error("unexpected clipboard")
        }
    }, tasks, ActionLog())


    private var time = 1000L
    private val ui = UiMcpFacade(core) { time }
    private class Driver : PhoneDriver {
        override val screen = PhoneScreen.MAIN
        var pkg = "example.app"
        var window = "7"
        var serial = 0
        var reads = 0
        var writes = 0
        val actions = mutableListOf<PhoneAction>()
        var sensitive = false
        var enabled = true
        var truncated = false
        var extraPackage: String? = null
        var sensitiveOtherWindow = false
        override fun availability() = Availability(true)
        override fun foregroundPackage() = pkg
        override fun inspectUiTarget() = UiTargetState(pkg, window, "s$serial")
        override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
            reads++; serial++
            val target = inspectUiTarget()
            val node = UiObservedElement(1, window, pkg, "textbox", if (sensitive) "secret" else "Search",
                "example.app:id/search", 0, 0, 20, 20, enabled, true, true, sensitive, true, true)
            return ScreenSnapshot("s$serial", "Example", "Example", pkg,
                image = if (image) byteArrayOf(1, 2) else null, imageMime = if (image) "image/png" else null,
                ui = UiObservation(target, listOf(node) + if (sensitiveOtherWindow) listOf(node.copy(id = 2, windowId = "8", sensitive = true)) else emptyList(),
                    truncated, setOfNotNull(pkg, extraPackage)))
        }
        override fun target(screenId: String, elementId: Int) = TargetInfo(screenId, elementId,
            "输入框", "Search", pkg, "Example", sensitive, sensitive, true)
        override fun performChecked(action: PhoneAction, guard: UiActionGuard, allowed: (() -> Boolean)?,
                                    actionSourcesAllowed: ((Set<String>) -> Boolean)?): ActionResult {
            if (!guard.matches(inspectUiTarget()) || action.screenId != guard.screenId)
                return ActionResult(false, "changed", errorCode = "STALE_ELEMENT")
            return perform(action)
        }
        override fun perform(action: PhoneAction): ActionResult { actions += action; writes++; return ActionResult(true, "done", observe(true, false)) }
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unexpected collect")
        override fun open(target: PhoneAppIdentity) = error("unexpected launch")
    }
    @After fun close() = core.worker.shutdown()
    private fun call(name: String, vararg args: Pair<String, Any?>) = ui.call(name, mapOf("sessionId" to "agent") + args)
    private fun data(out: ToolOutput) = out.structuredContent!!["data"] as Map<*, *>
    private fun snap(vararg args: Pair<String, Any?>): String {
        val out = call("ui_snapshot", *args); assertFalse(out.text, out.isError)
        return data(out)["snapshotId"] as String
    }
    private fun error(code: String, out: ToolOutput) {
        assertTrue(out.text, out.isError)
        assertEquals(code, (out.structuredContent!!["error"] as Map<*, *>)["code"])
    }
    @Test fun separateCatalogAndStructuredEnvelope() {
        assertEquals(12, UiToolCatalog.names.size)
        assertEquals(13, PhoneToolCatalog.names.size)
        assertTrue(UiToolCatalog.names.intersect(PhoneToolCatalog.names).isEmpty())
        val result = call("ui_snapshot")
        assertEquals(Json.write(result.structuredContent), result.text)
        assertFalse(result.text.contains("fingerprint"))
        assertFalse(result.text.contains("secret"))
    }
    @Test fun callerCannotInventTargetOrInjectLegacyArguments() {
        error("POLICY_DENIED", call("ui_snapshot", "applicationId" to "invented.app"))
        error("INVALID_ARGUMENT", call("ui_click", "snapshotId" to "x", "refId" to "e1", "x" to 1))
        error("INVALID_ARGUMENT", call("ui_snapshot", "maxNodes" to 0))
        assertEquals(0, main.reads); assertEquals(0, main.writes)
    }
    @Test fun refsAreConsumedBeforeWritesAndNotReplayed() {
        val id = snap()
        val result = call("ui_set_text", "snapshotId" to id, "refId" to "e1", "text" to "query")
        assertFalse(result.text, result.isError); assertEquals("observed", data(result)["phase"])
        error("STALE_ELEMENT", call("ui_click", "snapshotId" to id, "refId" to "e1"))
        assertEquals(1, main.writes); assertTrue(asked > 0)
    }
    @Test fun anotherSessionAndRawObservationInvalidateRefs() {
        val id = snap()
        val other = ui.call("ui_snapshot", mapOf("sessionId" to "other")); assertFalse(other.isError)
        error("STALE_ELEMENT", call("ui_click", "snapshotId" to id, "refId" to "e1"))
        val fresh = snap(); core.call(PhoneToolCatalog.SCREEN, emptyMap())
        error("STALE_ELEMENT", call("ui_click", "snapshotId" to fresh, "refId" to "e1"))
        assertEquals(0, main.writes)
    }
    @Test fun identityChangeDuringConfirmationPreventsNativeWrite() {
        val id = snap(); confirm = { main.window = "8"; Decision.ALLOW_ONCE }
        error("STALE_ELEMENT", call("ui_click", "snapshotId" to id, "refId" to "e1"))
        assertEquals(0, main.writes)
    }
    @Test fun replacedTaskCannotUseOldKeyConfirmation() {
        confirm = { tasks.endTask(); tasks.begin(); Decision.ALLOW_ONCE }
        assertTrue(call("ui_press_key", "key" to "back").isError)
        assertEquals(0, main.writes)
    }
    @Test fun disabledSecretAndWrongRoleCannotBeWritten() {
        main.enabled = false
        error("ACTION_UNSUPPORTED", call("ui_set_text", "snapshotId" to snap(), "refId" to "e1", "text" to "q"))
        main.enabled = true; main.sensitive = true
        val secret = call("ui_snapshot"); assertFalse(secret.text.contains("secret"))
        error("ACTION_UNSUPPORTED", call("ui_click", "snapshotId" to data(secret)["snapshotId"], "refId" to "e1"))
        main.sensitive = false
        error("STALE_ELEMENT", call("ui_click", "snapshotId" to snap(), "refId" to "e1", "expectedRole" to "button"))
        assertEquals(0, main.writes)
    }
    @Test fun policyChangeAndUnauthorizedOverlayDiscardObservation() {
        policies = PhonePolicies(uiAllowedPackages = listOf("another.app"))
        error("POLICY_DENIED", call("ui_snapshot")); assertEquals(0, main.reads)
        policies = PhonePolicies(uiAllowedPackages = listOf("example.app")); main.extraPackage = "another.app"
        error("SECURE_CONTENT", call("ui_snapshot")); assertEquals(0, main.writes)
    }
    @Test fun secretAndIncompleteScreenshotsNeverReturnImage() {
        main.sensitive = true
        val secret = call("ui_screenshot"); error("SECURE_CONTENT", secret); assertNull(secret.image)
        main.sensitive = false; main.truncated = true
        val incomplete = call("ui_screenshot"); error("SECURE_CONTENT", incomplete); assertNull(incomplete.image)
    }
    @Test fun screenshotChecksSensitiveFieldsOutsideSelectedWindow() {
        main.sensitiveOtherWindow = true
        val result = call("ui_screenshot")
        error("SECURE_CONTENT", result)
        assertNull(result.image)
        // Ordinary snapshots still expose only elements from the selected foreground window.
        assertEquals(1, (data(call("ui_snapshot"))["elements"] as List<*>).size)
    }
    @Test fun resetAndSessionExpiryRetireRefs() {
        val id = snap(); ui.reset()
        error("STALE_ELEMENT", call("ui_click", "snapshotId" to id, "refId" to "e1"))
        snap(); time += 2 * 60 * 60 * 1000L
        error("INVALID_ARGUMENT", call("ui_snapshot"))
        assertEquals(0, main.writes)
    }
    @Test fun scrollBoundsAndExplicitRefAreValidatedBeforeDispatch() {
        error("INVALID_ARGUMENT", call("ui_scroll", "amount" to 11))
        error("INVALID_ARGUMENT", call("ui_scroll", "refId" to "e1"))
        assertEquals(0, main.reads); assertEquals(0, main.writes)
        val result = call("ui_scroll", "direction" to "down", "amount" to 2)
        assertFalse(result.text, result.isError); assertEquals(2, main.writes)
    }
    @Test fun unifiedLaunchRefusesLegacyAndUnknownSurfaces() {
        assertTrue(PhoneBridge.surfaceAllowed("ui", true))
        assertFalse(PhoneBridge.surfaceAllowed("phone", true))
        assertFalse(PhoneBridge.surfaceAllowed("unknown", true))
        assertFalse(PhoneBridge.surfaceAllowed("ui", false))
        assertTrue(PhoneBridge.surfaceAllowed("phone", false))
    }
    @Test fun blockedReadRemovesTypedObservationTogetherWithTextAndImage() {
        val screen = main.observe(true, true)
        policies = PhonePolicies(denyPackages = listOf("example.app"))
        val filtered = core.safety.filterScreen(screen)
        assertNull(filtered.ui); assertNull(filtered.image)
        assertFalse(filtered.text.contains("Search"))
    }
    @Test fun mcpResultPublishesStructuredContentOnlyForUnifiedSurface() {
        val request = Json.write(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
            "params" to mapOf("name" to "ui_snapshot", "arguments" to emptyMap<String, Any?>())))
        val reply = Json.parse(McpServer(UiToolCatalog.tools, { name, args, _ -> ui.call(name, args) }).handle(request)!!) as Map<*, *>
        assertNotNull((reply["result"] as Map<*, *>)["structuredContent"])
        val legacy = McpServer(PhoneToolCatalog.listJson(), { name, args, _ -> core.call(name, args) }).handle(request.replace("ui_snapshot", "screen"))!!
        assertFalse(legacy.contains("structuredContent"))
    }
    @Test fun globalScrollAlwaysPassesAnElementFromSelectedWindow() {
        val result = call("ui_scroll", "amount" to 1)
        assertFalse(result.text, result.isError)
        assertEquals(1, (main.actions.single() as PhoneAction.Scroll).elementId)
    }
    @Test fun lockedPhoneAndMalformedPolicyFailClosed() {
        locked = true; error("PROVIDER_UNAVAILABLE", call("ui_list_devices"))
        locked = false
        policies = PhonePolicies.fromMap(mapOf("uiAllowedPackages" to "example.app"))
        error("POLICY_DENIED", call("ui_snapshot")); assertEquals(0, main.reads)
    }
}
