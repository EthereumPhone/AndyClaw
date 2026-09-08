package org.ethereumphone.andyclaw.agentwallet

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger

class AgentWalletTest {

    // ── SubWalletResult ──────────────────────────────────────────────
    //
    // The SDK does not throw when a send fails; it returns "Error: …". Treating that as a
    // hash is what made failed agent sends report success and land in the history table.

    @Test
    fun `a real userOpHash parses as success`() {
        val hash = "0x" + "ab".repeat(32)
        val result = SubWalletResult.parse(hash, "ETH", "Base")
        assertTrue(result is SubWalletResult.Success)
        assertEquals(hash, (result as SubWalletResult.Success).userOpHash)
    }

    @Test
    fun `an error string never parses as success`() {
        val result = SubWalletResult.parse(
            "Error: UserOperation reverted",
            "ETH",
            "Base",
        )
        assertTrue(result is SubWalletResult.Failure)
    }

    @Test
    fun `AA21 becomes an actionable gas message naming chain and token`() {
        val result = SubWalletResult.parse(
            "Error: AA21 didn't pay prefund",
            "POL",
            "Polygon",
        )
        assertTrue(result is SubWalletResult.Failure)
        val message = (result as SubWalletResult.Failure).message
        assertTrue(message.contains("POL"))
        assertTrue(message.contains("Polygon"))
        assertFalse("the raw AA21 code should not reach the user", message.contains("AA21"))
    }

    @Test
    fun `a hex string of the wrong length is not a hash`() {
        // Short, long, and non-hex all have to fail closed rather than be recorded.
        assertTrue(SubWalletResult.parse("0xabc", "ETH", "Base") is SubWalletResult.Failure)
        assertTrue(SubWalletResult.parse("0x" + "ab".repeat(33), "ETH", "Base") is SubWalletResult.Failure)
        assertTrue(SubWalletResult.parse("0x" + "zz".repeat(32), "ETH", "Base") is SubWalletResult.Failure)
    }

    @Test
    fun `an empty response is a failure, not a silent success`() {
        assertTrue(SubWalletResult.parse("", "ETH", "Base") is SubWalletResult.Failure)
        assertTrue(SubWalletResult.parse("   ", "ETH", "Base") is SubWalletResult.Failure)
    }

    // ── AmountFormat ─────────────────────────────────────────────────

    @Test
    fun `whole and fractional amounts convert to base units`() {
        assertEquals(BigInteger("100000000"), AmountFormat.toBaseUnits("100", 6))
        assertEquals(BigInteger("500000000000000000"), AmountFormat.toBaseUnits("0.5", 18))
        assertEquals(BigInteger.ZERO, AmountFormat.toBaseUnits("0", 18))
    }

    @Test
    fun `over-precise input is rejected rather than throwing`() {
        // The old inline `toBigIntegerExact()` threw ArithmeticException here.
        assertNull(AmountFormat.toBaseUnits("1.1234567", 6))
        assertNull(AmountFormat.toBaseUnits("0.0000001", 6))
    }

    @Test
    fun `junk and negative amounts are rejected`() {
        assertNull(AmountFormat.toBaseUnits("", 18))
        assertNull(AmountFormat.toBaseUnits("abc", 18))
        assertNull(AmountFormat.toBaseUnits("-1", 18))
    }

    @Test
    fun `base units round-trip back to the typed amount`() {
        val base = AmountFormat.toBaseUnits("123.456", 18)!!
        assertEquals("123.456", AmountFormat.fromBaseUnits(base, 18))
    }

    @Test
    fun `display never rounds dust up to a plain zero`() {
        val dust = BigInteger.ONE // 1 wei
        assertEquals("<0.000001", AmountFormat.formatForDisplay(dust, 18, maxDecimals = 6))
        assertEquals("0", AmountFormat.formatForDisplay(BigInteger.ZERO, 18))
    }

    // ── EthAddress ───────────────────────────────────────────────────

    @Test
    fun `a correctly checksummed address is accepted`() {
        assertTrue(EthAddress.isValid("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    }

    @Test
    fun `a mixed-case address with a bad checksum is rejected`() {
        // Same address, one character's case flipped.
        assertFalse(EthAddress.isValid("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1Beaed"))
        assertNotNull(EthAddress.validationError("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1Beaed"))
    }

    @Test
    fun `single-case addresses carry no checksum and are accepted`() {
        assertTrue(EthAddress.isValid("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
        assertTrue(EthAddress.isValid("0x5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED"))
    }

    @Test
    fun `malformed addresses are rejected with a reason`() {
        assertNotNull(EthAddress.validationError(""))
        assertNotNull(EthAddress.validationError("0x123"))
        assertNotNull(EthAddress.validationError("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
    }

    @Test
    fun `ens names are recognised and not treated as addresses`() {
        assertTrue(EthAddress.looksLikeEns("vitalik.eth"))
        assertTrue(EthAddress.looksLikeEns("Vitalik.ETH"))
        assertFalse(EthAddress.looksLikeEns("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
    }

    // ── Key-safety guards ────────────────────────────────────────────
    //
    // The sub-account's P-256 key is owner[0] of its CREATE2 address, and there is no
    // rescue path if it is lost: the OS wallet (owner[1]) cannot produce an ERC-1271
    // signature over a raw hash. Destroying or auth-binding that key is unrecoverable
    // fleet-wide, so these guards fail the build rather than relying on review.

    private val appModuleRoot = File("src/main/java/org/ethereumphone/andyclaw")

    private fun appSources(): List<File> =
        appModuleRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Source with comment lines removed. The guards below are documented in prose that
     * names the very calls they forbid, so a raw text search matches its own explanation.
     */
    private fun File.code(): String = readText().lines()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    @Test
    fun `no code deletes a keystore entry`() {
        val offenders = appSources().filter { file ->
            val text = file.code()
            text.contains("deleteEntry") || text.contains("deleteKey(")
        }
        assertTrue(
            "Deleting a keystore entry would change the agent wallet address and strand its " +
                "funds permanently. Offending files: ${offenders.map { it.name }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no code binds user authentication to a key`() {
        val offenders = appSources().filter { file ->
            // The prose in SpendAuthGate deliberately names these; only real calls count.
            val text = file.code()
            text.contains("setUserAuthenticationRequired(") ||
                text.contains("setUserAuthenticationParameters(") ||
                text.contains("setInvalidatedByBiometricEnrollment(")
        }
        assertTrue(
            "An auth-bound key is invalidated by any fingerprint enrolment change, which for " +
                "p256_walletsdk means permanent loss of the agent wallet. Offending files: " +
                "${offenders.map { it.name }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the spend auth gate uses no CryptoObject`() {
        val gate = File("src/main/java/org/ethereumphone/andyclaw/ui/components/SpendAuthGate.kt")
        assertTrue("SpendAuthGate.kt is missing", gate.exists())
        val body = gate.code()
        assertFalse(
            "The confirmation gate must authenticate the action, never the wallet key.",
            body.contains("CryptoObject"),
        )
    }

    @Test
    fun `the sub-account SDK stays pinned`() {
        // Bumping this silently would swap out the key-handling code the guards above
        // assume. A deliberate bump should update this test in the same commit.
        val gradle = File("build.gradle.kts").readText()
        assertTrue(
            "DgenSubAccountSDK must stay pinned to the reviewed version",
            gradle.contains("com.github.EthereumPhone:DgenSubAccountSDK:0.2.0"),
        )
    }
}
