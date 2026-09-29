package org.ethereumphone.andyclaw.agentwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendOutcomeAndTokenCheckTest {

    private val hash = "0x" + "ab".repeat(32)
    private val usdcOnBase = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"

    // ── UserWalletResult ────────────────────────────────────────────

    @Test
    fun `a userOpHash is a submission`() {
        assertEquals(UserWalletResult.Submitted(hash), UserWalletResult.parse(" $hash "))
    }

    @Test
    fun `decline is a decline`() {
        assertEquals(UserWalletResult.Declined, UserWalletResult.parse("decline"))
    }

    @Test
    fun `a bundler rejection is a failure, not a submission`() {
        val outcome = UserWalletResult.parse("Error: AA21 didn't pay prefund")
        assertTrue(outcome is UserWalletResult.Failed)
        assertEquals("AA21 didn't pay prefund", (outcome as UserWalletResult.Failed).message)
    }

    @Test
    fun `anything else starting with 0x is not proof of submission`() {
        assertTrue(UserWalletResult.parse("0xdeadbeef") is UserWalletResult.Failed)
        assertTrue(UserWalletResult.parse("") is UserWalletResult.Failed)
        assertTrue(UserWalletResult.parse(null) is UserWalletResult.Failed)
        assertTrue(UserWalletResult.parse("error") is UserWalletResult.Failed)
    }

    // ── Erc20Check.verdict ─────────────────────────────────────────

    private fun verdict(code: String?, decimals: Int?, claimed: Int?) =
        Erc20Check.verdict(usdcOnBase, "Base", Erc20Check.OnChain(code, decimals), claimed)

    @Test
    fun `an address with no code on this chain is refused`() {
        assertTrue(verdict("0x", null, 6) is Erc20Check.Verdict.Refused)
        assertTrue(verdict("", null, 6) is Erc20Check.Verdict.Refused)
    }

    @Test
    fun `an unreadable chain is refused rather than assumed fine`() {
        assertTrue(verdict(null, null, 6) is Erc20Check.Verdict.Refused)
    }

    @Test
    fun `decimals that disagree with the contract are refused`() {
        val v = verdict("0x6080", 6, 18)
        assertTrue(v is Erc20Check.Verdict.Refused)
        assertTrue((v as Erc20Check.Verdict.Refused).reason.contains("6 decimals"))
    }

    @Test
    fun `the contract's decimals are used when none were claimed`() {
        assertEquals(Erc20Check.Verdict.Ok(6), verdict("0x6080", 6, null))
        assertEquals(Erc20Check.Verdict.Ok(6), verdict("0x6080", 6, 6))
    }

    @Test
    fun `a contract without decimals() needs them from the caller`() {
        assertEquals(Erc20Check.Verdict.Ok(8), verdict("0x6080", null, 8))
        assertTrue(verdict("0x6080", null, null) is Erc20Check.Verdict.Refused)
    }

    // ── ENS names ──────────────────────────────────────────────────

    @Test
    fun `look-alike ens names are refused, plain ones normalised`() {
        assertEquals("vitalik.eth", EthAddress.normalizeEnsName(" Vitalik.ETH "))
        // Cyrillic 'а' in place of Latin 'a'.
        assertNull(EthAddress.normalizeEnsName("vitаlik.eth"))
        assertNull(EthAddress.normalizeEnsName("0x1234567890123456789012345678901234567890"))
    }
}
