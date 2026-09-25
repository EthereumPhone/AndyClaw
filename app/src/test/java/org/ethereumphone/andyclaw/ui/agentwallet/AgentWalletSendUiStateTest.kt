package org.ethereumphone.andyclaw.ui.agentwallet

import org.ethereumphone.andyclaw.agentwallet.AmountFormat
import org.ethereumphone.andyclaw.agentwallet.AmountFormat.UserAmount
import org.ethereumphone.andyclaw.agentwallet.ChainBalances
import org.ethereumphone.andyclaw.agentwallet.EthAddress
import org.ethereumphone.andyclaw.agentwallet.GasBudget
import org.ethereumphone.andyclaw.agentwallet.TokenBalance
import org.ethereumphone.andyclaw.agentwallet.UserOpLookup
import org.ethereumphone.andyclaw.agentwallet.WalletIntegrity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The send screen's state decides what leaves the agent wallet. These pin down that it is
 * exactly what the user was shown: the address they see, the amount they meant, and never a
 * burn address or a balance they need for gas.
 */
class AgentWalletSendUiStateTest {

    private val ether = BigInteger.TEN.pow(18)
    private fun eth(n: String) = java.math.BigDecimal(n).movePointRight(18).toBigIntegerExact()

    private val anna = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val bob = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val agent = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
    private val usdcBase = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"

    private val nativeEth = TokenBalance("ETH", "Ether", 18, null, ether)
    private val usdc = TokenBalance("USDC", "USD Coin", 6, usdcBase, BigInteger.valueOf(250_000_000))
    private val base = ChainBalances(8453, nativeEth, listOf(usdc))

    private fun state(
        recipient: String = anna,
        amount: String = "0.5",
        token: TokenBalance = nativeEth,
        chain: ChainBalances = base,
        reserve: BigInteger? = eth("0.01"),
        separator: Char = '.',
    ) = AgentWalletSendUiState(
        loading = false,
        address = agent,
        integrity = WalletIntegrity.Ok(agent),
        balances = listOf(chain),
        selectedChainId = chain.chainId,
        selectedToken = token,
        amount = amount,
        recipient = recipient,
        decimalSeparator = separator,
        gasReserves = if (reserve != null) mapOf(chain.chainId to reserve) else emptyMap(),
    )

    // ── ENS ────────────────────────────────────────────────────────────

    @Test
    fun `a resolved name sends to its answer`() {
        val s = state(recipient = "alice.eth").copy(resolvedRecipient = anna, resolvedFor = "alice.eth")
        assertEquals(anna, s.effectiveRecipient)
    }

    @Test
    fun `an answer for another name is ignored once the field has changed`() {
        val s = state(recipient = "bob.eth").copy(resolvedRecipient = anna, resolvedFor = "alice.eth")
        assertNull(s.effectiveRecipient)
        assertNull(s.sendRequest())
    }

    @Test
    fun `a typed address wins over a stale lookup`() {
        val s = state(recipient = bob).copy(resolvedRecipient = anna, resolvedFor = "alice.eth")
        assertEquals(bob, s.effectiveRecipient)
    }

    @Test
    fun `a name typed in capitals is the same name`() {
        val s = state(recipient = "Alice.ETH").copy(resolvedRecipient = anna, resolvedFor = "alice.eth")
        assertEquals(anna, s.effectiveRecipient)
    }

    @Test
    fun `a look-alike Unicode name is refused, not resolved`() {
        // Cyrillic "а" in place of the Latin one.
        val s = state(recipient = "аlice.eth")
        assertNull(s.ensName)
        assertNull(s.effectiveRecipient)
        assertTrue(s.recipientError!!.contains("plain"))
        assertNull(EthAddress.normalizeEnsName("vitalik.eth​"))
        assertEquals("vitalik.eth", EthAddress.normalizeEnsName("  Vitalik.eth "))
    }

    // ── Zero address ───────────────────────────────────────────────────

    @Test
    fun `the zero address is never a recipient`() {
        val zero = "0x" + "0".repeat(40)
        assertTrue(EthAddress.isZero(zero))
        val s = state(recipient = zero)
        assertNull(s.effectiveRecipient)
        assertTrue(s.recipientError!!.contains("zero address"))
        assertNull(s.sendRequest())
    }

    // ── The request that is confirmed ──────────────────────────────────

    @Test
    fun `the request carries the whole checksummed address and the canonical amount`() {
        val s = state(recipient = anna.lowercase(), amount = "0,50", separator = ',')
        val request = s.sendRequest()!!
        assertEquals(anna, request.recipient)
        assertEquals("0.5", request.amountDisplay)
        assertEquals(eth("0.5"), request.amountBaseUnits)
        assertEquals("ETH", request.tokenSymbol)
        assertNull(request.tokenContract)
        assertEquals(8453, request.chainId)
    }

    @Test
    fun `a refresh that only changes balances leaves the confirmed request the same`() {
        val before = state().sendRequest()
        val refreshed = base.copy(native = nativeEth.copy(raw = eth("2")))
        val after = state(chain = refreshed, token = refreshed.native).sendRequest()
        assertEquals(before, after)
    }

    @Test
    fun `an edited recipient is a different request`() {
        assertTrue(state().sendRequest() != state(recipient = bob).sendRequest())
    }

    @Test
    fun `nothing can be confirmed while a send is in flight`() {
        assertNull(state().copy(sending = true).sendRequest())
    }

    @Test
    fun `sending to the token's own contract is flagged before confirming`() {
        val s = state(recipient = usdcBase, token = usdc, amount = "1")
        assertNotNull(s.recipientWarning)
        assertEquals(s.recipientWarning, s.sendRequest()!!.warning)
    }

    // ── Selection after a refresh ──────────────────────────────────────

    @Test
    fun `the selected token is re-bound to its fresh balance`() {
        val fresh = base.copy(tokens = listOf(usdc.copy(raw = BigInteger.valueOf(10_000_000))))
        val rebound = AgentWalletSendUiState.rebindSelection(fresh, usdc)!!
        assertEquals(BigInteger.valueOf(10_000_000), rebound.raw)
        assertEquals(usdcBase, rebound.contractAddress)
    }

    @Test
    fun `a token spent to zero gives way to what the chain still holds`() {
        val spent = base.copy(tokens = emptyList())
        assertEquals(nativeEth, AgentWalletSendUiState.rebindSelection(spent, usdc))
    }

    // ── Max and gas ────────────────────────────────────────────────────

    @Test
    fun `Max on the native token leaves the gas reserve`() {
        val s = state(amount = "")
        assertEquals(eth("0.99"), s.maxSendable)
        assertEquals(eth("0.99"), AmountFormat.maxSendable(ether, isNative = true, gasReserve = eth("0.01")))
    }

    @Test
    fun `Max on a token is the whole balance`() {
        assertEquals(usdc.raw, state(token = usdc, amount = "").maxSendable)
        assertEquals(usdc.raw, AmountFormat.maxSendable(usdc.raw, isNative = false, gasReserve = null))
    }

    @Test
    fun `Max waits for the gas estimate rather than guessing`() {
        assertNull(state(amount = "", reserve = null).maxSendable)
        assertNull(AmountFormat.maxSendable(ether, isNative = true, gasReserve = null))
    }

    @Test
    fun `an amount that eats into the gas reserve says how much to leave`() {
        val s = state(amount = "0.995")
        assertTrue(s.amountError!!.startsWith("Leave ~0.01 ETH for gas"))
        assertNull(s.sendRequest())
        assertNull(state(amount = "0.99").amountError)
    }

    @Test
    fun `more than the balance is refused`() {
        assertEquals("More than the agent wallet holds", state(amount = "1.5", reserve = null).amountError)
    }

    @Test
    fun `a balance that could not be read is not zero`() {
        val unknown = base.copy(native = nativeEth.copy(raw = BigInteger.ZERO), nativeKnown = false)
        val s = state(chain = unknown, token = unknown.native)
        assertTrue(s.balanceUnknown)
        assertNull("no paid top-up offered on a failed read", s.gasWarning)
        assertNull(s.maxSendable)
        assertNull(s.sendRequest())
    }

    @Test
    fun `the gas top-up is sized from the chain's gas price`() {
        val gwei = BigInteger.TEN.pow(9)
        val deployed = GasBudget.reserveWei(gwei.multiply(BigInteger.TEN), deployed = true)
        assertEquals(BigInteger("9000000000000000"), deployed) // 10 gwei × 600k × 1.5
        val fresh = GasBudget.reserveWei(gwei.multiply(BigInteger.TEN), deployed = false)
        assertEquals(BigInteger("15000000000000000"), fresh) // 10 gwei × 1M × 1.5
        assertEquals(deployed.multiply(BigInteger.valueOf(3)), GasBudget.topUpWei(deployed))
        assertEquals(GasBudget.topUpWei(eth("0.01")), state().gasTopUpWei)
    }

    // ── Typed amounts, German and English ──────────────────────────────

    private fun parse(raw: String, sep: Char, decimals: Int = 18) = AmountFormat.parseUserAmount(raw, decimals, sep)

    private fun ok(raw: String, sep: Char, decimals: Int = 18) = parse(raw, sep, decimals) as UserAmount.Ok

    @Test
    fun `a German keypad's comma is a decimal separator`() {
        assertEquals(eth("0.5"), ok("0,5", ',').baseUnits)
        assertEquals("0.5", ok("0,5", ',').canonical)
        assertEquals(eth("0.5"), ok(",5", ',').baseUnits)
        assertEquals("a dot works too", eth("1.5"), ok("1.5", ',').baseUnits)
        assertEquals("1,000 is one on a German phone", eth("1"), ok("1,000", ',').baseUnits)
    }

    @Test
    fun `German thousands grouping is refused, not misread`() {
        assertTrue(parse("1.000", ',') is UserAmount.Invalid)
        assertTrue(parse("1.000.000", ',') is UserAmount.Invalid)
        assertTrue(parse("1.000,5", ',') is UserAmount.Invalid)
    }

    @Test
    fun `English reads dots and refuses comma grouping`() {
        assertEquals(eth("0.5"), ok("0.5", '.').baseUnits)
        assertEquals("an unambiguous comma is fine", eth("0.5"), ok("0,5", '.').baseUnits)
        assertEquals(eth("1"), ok("1.000", '.').baseUnits)
        assertTrue(parse("1,000", '.') is UserAmount.Invalid)
        assertTrue(parse("1,000.5", '.') is UserAmount.Invalid)
    }

    @Test
    fun `signs, exponents and stray characters are refused with a reason`() {
        for (raw in listOf("+1", "-1", "1e3", "1E-2", "1 000", "abc", "１", "0x10", ".", ",")) {
            val parsed = parse(raw, '.')
            assertTrue("$raw -> $parsed", parsed is UserAmount.Invalid)
            assertTrue((parsed as UserAmount.Invalid).message.isNotBlank())
        }
        assertEquals(UserAmount.Empty, parse("   ", ','))
    }

    @Test
    fun `more decimals than the token has are refused, trailing zeros are not`() {
        assertTrue(parse("1.1234567", '.', decimals = 6) is UserAmount.Invalid)
        assertEquals("1.5", ok("1.500000000", '.', decimals = 6).canonical)
        assertEquals("0", ok("0,000", ',').canonical)
    }

    @Test
    fun `the amount field's error is the parser's`() {
        assertTrue(state(amount = "1.000", separator = ',').amountError!!.contains("thousand"))
        assertEquals("Amount must be greater than zero", state(amount = "0").amountError)
    }

    // ── Explorer lookups ───────────────────────────────────────────────

    @Test
    fun `a receipt gives the transaction to open, and a missing one is pending`() {
        val tx = "0x" + "ab".repeat(32)
        assertEquals(
            UserOpLookup.Included(tx),
            UserOpLookup.fromReceiptResponse("""{"jsonrpc":"2.0","id":1,"result":{"success":true,"receipt":{"transactionHash":"$tx"}}}"""),
        )
        assertEquals(UserOpLookup.Pending, UserOpLookup.fromReceiptResponse("""{"jsonrpc":"2.0","id":1,"result":null}"""))
        assertEquals(UserOpLookup.Unavailable, UserOpLookup.fromReceiptResponse("""{"error":{"code":-32601}}"""))
        assertEquals(UserOpLookup.Unavailable, UserOpLookup.fromReceiptResponse("<html>"))
    }
}
