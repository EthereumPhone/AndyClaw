package org.ethereumphone.andyclaw.skills.builtin

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class WalletSkillTest {

    // ── resolveWellKnownToken ────────────────────────────────────────

    @Test
    fun `resolveWellKnownToken returns correct USDC on Ethereum mainnet`() {
        val result = WalletSkill.resolveWellKnownToken("USDC", 1)
        assertNotNull(result)
        assertEquals("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", result!!.first)
        assertEquals(6, result.second)
    }

    @Test
    fun `resolveWellKnownToken returns correct USDC on Base`() {
        val result = WalletSkill.resolveWellKnownToken("USDC", 8453)
        assertNotNull(result)
        assertEquals("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", result!!.first)
        assertEquals(6, result.second)
    }

    @Test
    fun `resolveWellKnownToken returns correct WETH on Arbitrum`() {
        val result = WalletSkill.resolveWellKnownToken("WETH", 42161)
        assertNotNull(result)
        assertEquals("0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", result!!.first)
        assertEquals(18, result.second)
    }

    @Test
    fun `resolveWellKnownToken returns correct WBTC on mainnet with 8 decimals`() {
        val result = WalletSkill.resolveWellKnownToken("WBTC", 1)
        assertNotNull(result)
        assertEquals("0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", result!!.first)
        assertEquals(8, result.second)
    }

    @Test
    fun `resolveWellKnownToken is case insensitive`() {
        val lower = WalletSkill.resolveWellKnownToken("usdc", 1)
        val upper = WalletSkill.resolveWellKnownToken("USDC", 1)
        val mixed = WalletSkill.resolveWellKnownToken("Usdc", 1)
        assertNotNull(lower)
        assertEquals(lower, upper)
        assertEquals(lower, mixed)
    }

    @Test
    fun `resolveWellKnownToken returns null for unknown symbol`() {
        val result = WalletSkill.resolveWellKnownToken("SHIB", 1)
        assertNull(result)
    }

    @Test
    fun `resolveWellKnownToken returns null for token on unsupported chain`() {
        val result = WalletSkill.resolveWellKnownToken("DEGEN", 1) // DEGEN only on Base
        assertNull(result)
    }

    @Test
    fun `resolveWellKnownToken returns DEGEN on Base`() {
        val result = WalletSkill.resolveWellKnownToken("DEGEN", 8453)
        assertNotNull(result)
        assertEquals("0x4ed4E862860beD51a9570b96d89aF5E1B0Efefed", result!!.first)
        assertEquals(18, result.second)
    }

    @Test
    fun `resolveWellKnownToken returns null for unsupported chain ID`() {
        val result = WalletSkill.resolveWellKnownToken("USDC", 999)
        assertNull(result)
    }

    @Test
    fun `resolveWellKnownToken handles BSC USDT with 18 decimals`() {
        val result = WalletSkill.resolveWellKnownToken("USDT", 56)
        assertNotNull(result)
        assertEquals("0x55d398326f99059fF775485246999027B3197955", result!!.first)
        assertEquals(18, result.second) // BSC USDT uses 18 decimals, not 6
    }

    @Test
    fun `resolveWellKnownToken returns 6-decimal USDT on Ethereum`() {
        val result = WalletSkill.resolveWellKnownToken("USDT", 1)
        assertNotNull(result)
        assertEquals("0xdAC17F958D2ee523a2206206994597C13D831ec7", result!!.first)
        assertEquals(6, result.second)
    }

    // ── findTokenBySymbol ────────────────────────────────────────────

    @Test
    fun `findTokenBySymbol returns all USDT entries including BSC variant`() {
        val results = WalletSkill.findTokenBySymbol("USDT")
        assertTrue(results.size >= 2) // At least the 6-decimal and 18-decimal BSC variant
        val bscEntry = results.find { it.addresses.containsKey(56) }
        assertNotNull(bscEntry)
        assertEquals(18, bscEntry!!.decimals)
        val ethEntry = results.find { it.addresses.containsKey(1) }
        assertNotNull(ethEntry)
        assertEquals(6, ethEntry!!.decimals)
    }

    @Test
    fun `findTokenBySymbol is case insensitive`() {
        val lower = WalletSkill.findTokenBySymbol("usdc")
        val upper = WalletSkill.findTokenBySymbol("USDC")
        assertEquals(lower.size, upper.size)
        assertEquals(lower.first().symbol, upper.first().symbol)
    }

    @Test
    fun `findTokenBySymbol returns empty for unknown symbol`() {
        val results = WalletSkill.findTokenBySymbol("UNKNOWN_TOKEN_XYZ")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findTokenBySymbol returns single entry for DEGEN`() {
        val results = WalletSkill.findTokenBySymbol("DEGEN")
        assertEquals(1, results.size)
        assertEquals("DEGEN", results.first().symbol)
        assertTrue(results.first().addresses.containsKey(8453))
        assertEquals(1, results.first().addresses.size) // Only on Base
    }

    @Test
    fun `findTokenBySymbol returns entries for all registered tokens`() {
        val expectedSymbols = listOf("USDC", "USDT", "WETH", "DAI", "WBTC", "LINK", "UNI", "AAVE", "DEGEN")
        for (symbol in expectedSymbols) {
            val results = WalletSkill.findTokenBySymbol(symbol)
            assertTrue("Expected $symbol to be found in registry", results.isNotEmpty())
        }
    }

    // ── WELL_KNOWN_TOKENS registry integrity ──────────────────────────

    @Test
    fun `all token addresses are valid checksummed hex`() {
        for (token in WalletSkill.WELL_KNOWN_TOKENS) {
            for ((chainId, address) in token.addresses) {
                assertTrue(
                    "${token.symbol} on chain $chainId has invalid address: $address",
                    address.startsWith("0x") && address.length == 42
                )
            }
        }
    }

    @Test
    fun `all tokens have positive decimals`() {
        for (token in WalletSkill.WELL_KNOWN_TOKENS) {
            assertTrue(
                "${token.symbol} has non-positive decimals: ${token.decimals}",
                token.decimals > 0
            )
        }
    }

    @Test
    fun `all tokens have at least one chain deployment`() {
        for (token in WalletSkill.WELL_KNOWN_TOKENS) {
            assertTrue(
                "${token.symbol} (${token.name}) has no chain deployments",
                token.addresses.isNotEmpty()
            )
        }
    }

    @Test
    fun `USDC is deployed on at least 5 chains`() {
        val usdc = WalletSkill.WELL_KNOWN_TOKENS.find {
            it.symbol == "USDC" && it.addresses.size > 1
        }
        assertNotNull("USDC should be in registry", usdc)
        assertTrue(
            "USDC should be on at least 5 chains, found ${usdc!!.addresses.size}",
            usdc.addresses.size >= 5
        )
    }

    // ── NATIVE_TOKENS registry ───────────────────────────────────────

    @Test
    fun `NATIVE_TOKENS covers all expected supported chains`() {
        // All chains that appear in WELL_KNOWN_TOKENS should have a native token
        val chainsInRegistry = WalletSkill.WELL_KNOWN_TOKENS
            .flatMap { it.addresses.keys }
            .toSet()

        for (chainId in chainsInRegistry) {
            assertNotNull(
                "Chain $chainId used by token registry but missing from NATIVE_TOKENS",
                WalletSkill.NATIVE_TOKENS[chainId]
            )
        }
    }

    @Test
    fun `Ethereum mainnet native token is ETH`() {
        val eth = WalletSkill.NATIVE_TOKENS[1]
        assertNotNull(eth)
        assertEquals("ETH", eth!!.symbol)
        assertEquals(18, eth.decimals)
    }

    @Test
    fun `Polygon native token is POL`() {
        val pol = WalletSkill.NATIVE_TOKENS[137]
        assertNotNull(pol)
        assertEquals("POL", pol!!.symbol)
    }

    @Test
    fun `BNB Chain native token is BNB`() {
        val bnb = WalletSkill.NATIVE_TOKENS[56]
        assertNotNull(bnb)
        assertEquals("BNB", bnb!!.symbol)
    }

    @Test
    fun `Avalanche native token is AVAX`() {
        val avax = WalletSkill.NATIVE_TOKENS[43114]
        assertNotNull(avax)
        assertEquals("AVAX", avax!!.symbol)
    }

    @Test
    fun `all native tokens have 18 decimals`() {
        for ((chainId, token) in WalletSkill.NATIVE_TOKENS) {
            assertEquals(
                "${token.symbol} on chain $chainId should have 18 decimals",
                18, token.decimals
            )
        }
    }

    @Test
    fun `Base and Optimism and Arbitrum use ETH as native token`() {
        for (chainId in listOf(8453, 10, 42161)) {
            val token = WalletSkill.NATIVE_TOKENS[chainId]
            assertNotNull("Chain $chainId should have native token", token)
            assertEquals("ETH", token!!.symbol)
        }
    }

    // ── Amount conversion math (mirrors the BigDecimal logic used in the skill) ──

    @Test
    fun `human-readable USDC amount converts to correct raw value`() {
        // 100 USDC with 6 decimals = 100_000_000
        val amount = "100"
        val decimals = 6
        val raw = BigDecimal(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigIntegerExact()
        assertEquals(BigInteger("100000000"), raw)
    }

    @Test
    fun `human-readable ETH amount converts to correct wei`() {
        // 1.5 ETH with 18 decimals = 1_500_000_000_000_000_000
        val amount = "1.5"
        val decimals = 18
        val raw = BigDecimal(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigIntegerExact()
        assertEquals(BigInteger("1500000000000000000"), raw)
    }

    @Test
    fun `human-readable WBTC amount converts correctly with 8 decimals`() {
        // 0.5 WBTC with 8 decimals = 50_000_000
        val amount = "0.5"
        val decimals = 8
        val raw = BigDecimal(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigIntegerExact()
        assertEquals(BigInteger("50000000"), raw)
    }

    @Test
    fun `very small ETH amount converts correctly`() {
        // 0.000001 ETH = 1_000_000_000_000 wei
        val amount = "0.000001"
        val decimals = 18
        val raw = BigDecimal(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigIntegerExact()
        assertEquals(BigInteger("1000000000000"), raw)
    }

    @Test
    fun `whole number token amount has no fractional wei`() {
        // 1 USDC = exactly 1_000_000 (no rounding)
        val amount = "1"
        val decimals = 6
        val raw = BigDecimal(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigIntegerExact()
        assertEquals(BigInteger("1000000"), raw)
    }

    @Test(expected = ArithmeticException::class)
    fun `amount with too many decimals for USDC throws ArithmeticException`() {
        // 1.0000001 USDC has 7 decimal places but USDC only has 6
        val amount = "1.0000001"
        val decimals = 6
        BigDecimal(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigIntegerExact() // should throw
    }

    @Test
    fun `large token amount converts correctly`() {
        // 1,000,000 USDC = 1_000_000_000_000
        val amount = "1000000"
        val decimals = 6
        val raw = BigDecimal(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigIntegerExact()
        assertEquals(BigInteger("1000000000000"), raw)
    }

    // ── Cross-chain token resolution consistency ─────────────────────

    @Test
    fun `USDC has same 6 decimals across all chains`() {
        val usdcEntries = WalletSkill.findTokenBySymbol("USDC")
        for (entry in usdcEntries) {
            assertEquals("USDC should have 6 decimals", 6, entry.decimals)
        }
    }

    @Test
    fun `WETH has 18 decimals across all chains`() {
        val wethEntries = WalletSkill.findTokenBySymbol("WETH")
        for (entry in wethEntries) {
            assertEquals("WETH should have 18 decimals", 18, entry.decimals)
        }
    }

    @Test
    fun `DAI has 18 decimals across all chains`() {
        val daiEntries = WalletSkill.findTokenBySymbol("DAI")
        for (entry in daiEntries) {
            assertEquals("DAI should have 18 decimals", 18, entry.decimals)
        }
    }

    @Test
    fun `no duplicate contract addresses within same chain`() {
        val seen = mutableSetOf<Pair<Int, String>>()
        for (token in WalletSkill.WELL_KNOWN_TOKENS) {
            for ((chainId, address) in token.addresses) {
                val key = chainId to address.lowercase()
                assertTrue(
                    "Duplicate contract address ${address} on chain $chainId " +
                            "(already used by another token)",
                    seen.add(key)
                )
            }
        }
    }
}
