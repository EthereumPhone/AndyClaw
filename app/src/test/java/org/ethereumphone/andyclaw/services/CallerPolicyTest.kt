package org.ethereumphone.andyclaw.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPC-02 / IPC-03: a package name is never enough to call the launcher service. */
class CallerPolicyTest {

    private val launcher = listOf(CallerPolicy.LAUNCHER_PACKAGE)
    private val systemUi = listOf(CallerPolicy.SYSTEMUI_PACKAGE)
    private val appUid = 10_234

    @Test
    fun `an app that only calls itself the launcher is refused`() {
        assertFalse(CallerPolicy.isAllowed(appUid, launcher, signatureMatch = false))
    }

    @Test
    fun `the system uid is let in`() {
        assertTrue(CallerPolicy.isAllowed(CallerPolicy.SYSTEM_UID, launcher + "android", signatureMatch = false))
    }

    @Test
    fun `a caller signed like AndyClaw is let in`() {
        assertTrue(CallerPolicy.isAllowed(appUid, launcher, signatureMatch = true))
        assertTrue(CallerPolicy.isAllowed(appUid, systemUi, signatureMatch = true))
    }

    @Test
    fun `the name is still required, whoever signed it`() {
        assertFalse(CallerPolicy.isAllowed(appUid, listOf("com.example.other"), signatureMatch = true))
        assertFalse(CallerPolicy.isAllowed(CallerPolicy.SYSTEM_UID, listOf("com.android.settings"), signatureMatch = true))
        assertFalse(CallerPolicy.isAllowed(appUid, null, signatureMatch = true))
    }

    @Test
    fun `the debug client is let in by name only when the build passes it`() {
        val bench = listOf("org.ethereumphone.andyclaw.agentbench")
        assertTrue(CallerPolicy.isAllowed(appUid, bench, signatureMatch = false, debugPackages = bench.toSet()))
        assertFalse(CallerPolicy.isAllowed(appUid, bench, signatureMatch = false))
    }

    @Test
    fun `lock-screen prompts come from an authentic SystemUI only`() {
        assertTrue(CallerPolicy.lockscreenAllowed(appUid, systemUi, signatureMatch = true))
        assertTrue(CallerPolicy.lockscreenAllowed(CallerPolicy.SYSTEM_UID, systemUi, signatureMatch = false))
        // A package that merely calls itself SystemUI.
        assertFalse(CallerPolicy.lockscreenAllowed(appUid, systemUi, signatureMatch = false))
        // The launcher, however authentic, sends its turns through sendPrompt.
        assertFalse(CallerPolicy.lockscreenAllowed(CallerPolicy.SYSTEM_UID, launcher, signatureMatch = true))
        assertFalse(CallerPolicy.lockscreenAllowed(appUid, launcher, signatureMatch = true))
    }

    @Test
    fun `a signature check is only asked for a name that could pass`() {
        assertTrue(CallerPolicy.needsSignatureCheck(appUid, launcher))
        assertFalse(CallerPolicy.needsSignatureCheck(CallerPolicy.SYSTEM_UID, launcher))
        assertFalse(CallerPolicy.needsSignatureCheck(appUid, listOf("com.example.other")))
    }
}
