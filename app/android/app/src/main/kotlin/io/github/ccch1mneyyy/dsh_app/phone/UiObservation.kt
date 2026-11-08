package io.github.ccch1mneyyy.dsh_app.phone

/** Android-free immutable copies. No native node, input contents, token or raw fingerprint escapes. */
data class UiTargetState(val packageName: String, val windowId: String, val screenId: String? = null,
                         val secure: Boolean = false) {
    fun identity() = mapOf("applicationId" to packageName, "windowId" to windowId, "deviceId" to "android-local")
}

data class UiActionGuard(val target: UiTargetState, val screenId: String? = null) {
    fun matches(current: UiTargetState) = !current.secure && current.packageName == target.packageName &&
        current.windowId == target.windowId && (screenId == null || current.screenId == screenId)
}

data class UiObservedElement(
    val id: Int, val windowId: String, val packageName: String?, val role: String, val name: String,
    val nativeId: String?, val x: Int, val y: Int, val width: Int, val height: Int,
    val enabled: Boolean, val visible: Boolean, val editable: Boolean, val sensitive: Boolean,
    val clickable: Boolean, val scrollable: Boolean, val highRisk: Boolean = false,
) {
    val actions: List<String> get() = if (!enabled || !visible || sensitive) emptyList() else buildList {
        if (clickable) add("click")
        if (editable) add("set_text")
        if (scrollable) add("scroll")
    }
    fun toMap(ref: String): Map<String, Any?> = linkedMapOf(
        "ref" to ref, "role" to role, "name" to if (sensitive) "[REDACTED]" else name,
        "nativeId" to nativeId, "bounds" to mapOf("x" to x, "y" to y, "width" to width, "height" to height),
        "enabled" to enabled, "visible" to visible, "actions" to actions,
        "extra" to mapOf("sensitive" to sensitive.toString(), "highRisk" to highRisk.toString(),
            "applicationId" to packageName, "windowId" to windowId),
    )
}

data class UiObservation(val target: UiTargetState, val elements: List<UiObservedElement>,
                         val truncated: Boolean, val packages: Set<String>)
