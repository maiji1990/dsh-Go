package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test

class UiGuestModeTest {
    @Test fun patchSelectsExactlyOneServerAndExplicitRelaySurface() {
        val legacy = PhoneGuest.PATCH_YAML
        val ui = PhoneGuest.UI_PATCH_YAML
        assertTrue(legacy.contains("serverName: phone"))
        assertFalse(legacy.contains("          - ui"))
        assertTrue(ui.contains("serverName: ui"))
        assertTrue(ui.contains("          - ${PhoneGuest.CONFIG}\n          - ui"))
        assertFalse(ui.contains("serverName: phone"))
        assertEquals(1, Regex("- insert:").findAll(ui).count())
    }
    @Test fun unrelatedPolicyUpdateKeepsSurfaceAndAllowedPackages() {
        val original = PhonePolicies(unifiedUi = true, uiAllowedPackages = listOf("example.app"))
        val updated = PhonePolicies.fromMap(mapOf("confirmMode" to "always"), original)
        assertTrue(updated.unifiedUi)
        assertEquals(listOf("example.app"), updated.uiAllowedPackages)
        assertEquals(updated, PhonePolicies.fromMap(updated.toMap()))
    }
}
