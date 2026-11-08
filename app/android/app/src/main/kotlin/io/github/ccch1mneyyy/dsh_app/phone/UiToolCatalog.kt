package io.github.ccch1mneyyy.dsh_app.phone

/** Separate surface: does not change the legacy phone catalog or its pinned schema. */
object UiToolCatalog {
    private fun string(description: String) = mapOf("type" to "string", "description" to description)
    private fun integer(description: String) = mapOf("type" to "integer", "description" to description)
    private val common = linkedMapOf("deviceId" to string("android-local"), "sessionId" to string("Agent session ID"))
    private val target = linkedMapOf("applicationId" to string("Expected package; never an authorization credential"),
        "windowId" to string("Expected Android accessibility window ID"))
    private val query = linkedMapOf("role" to string("Normalized element role"), "name" to string("Element label"),
        "nativeId" to string("Android resource ID"))
    private val ref = linkedMapOf("snapshotId" to string("Current UI snapshot ID"), "refId" to string("Scoped UI ref"))
    private val expected = linkedMapOf("expectedRole" to string("Expected role"), "expectedName" to string("Expected label"))
    private fun tool(name: String, fields: Map<String, Any?> = emptyMap(), required: List<String> = emptyList()) =
        linkedMapOf<String, Any?>("name" to name, "description" to "Unified local Android UI: $name",
            "inputSchema" to linkedMapOf("type" to "object", "properties" to (common + fields),
                "required" to required, "additionalProperties" to false))

    val tools = listOf(
        tool("ui_list_devices"), tool("ui_list_targets", target), tool("ui_get_capabilities"),
        tool("ui_snapshot", target + mapOf("mode" to string("compact | normal | full"), "maxNodes" to integer("1..2000"))),
        tool("ui_find", target + query + mapOf("snapshotId" to ref.getValue("snapshotId"))),
        tool("ui_get_element", ref, listOf("snapshotId", "refId")),
        tool("ui_click", target + ref + expected, listOf("snapshotId", "refId")),
        tool("ui_set_text", target + ref + expected + mapOf("text" to string("Non-sensitive input text")),
            listOf("snapshotId", "refId", "text")),
        tool("ui_press_key", target + mapOf("key" to string("back | home | recents | notifications")), listOf("key")),
        tool("ui_scroll", target + ref + mapOf("direction" to string("up | down | left | right"),
            "amount" to integer("1..10 native scroll steps; explicit ref requires amount=1"))),
        tool("ui_wait_for", target + query + mapOf("absent" to mapOf("type" to "boolean"), "timeoutMs" to integer("100..120000"))),
        tool("ui_screenshot", target),
    )
    val names = tools.map { it["name"] as String }.toSet()
}
