package org.ethereumphone.andyclaw.skills.builtin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ethereumphone.andyclaw.agentwallet.SubWalletFactory
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSkillRecipientTest {

    private val skill = WalletSkill(android.content.ContextWrapper(null))

    private fun params(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) ->
        k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())
    })

    @Test
    fun `the user's wallet tools refuse a malformed, mistyped or zero recipient before anything is sent`() = runBlocking {
        val bad = listOf(
            "0x123",                                        // short: web3j would left-pad it
            "0x0000000000000000000000000000000000000000",   // the zero address
            "0xa0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",   // one letter's case changed: checksum fails
            "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB4",    // one character dropped
        )
        for (to in bad) {
            val calls = mapOf(
                "send_native_token" to params("to" to to, "amount" to "0.01", "chain_id" to 1),
                "send_token" to params("to" to to, "amount" to "5", "chain_id" to 1, "symbol" to "USDC"),
                "propose_transaction" to params("to" to to, "value" to "1", "data" to "0", "chain_id" to 1),
                "propose_token_transfer" to params(
                    "contract_address" to "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                    "to" to to, "amount" to "5", "decimals" to 6, "chain_id" to 1,
                ),
            )
            for ((tool, input) in calls) {
                val result = skill.execute(tool, input, Tier.PRIVILEGED)
                assertTrue("$tool to $to: $result", result is SkillResult.Error && result.message.startsWith("Not sent"))
            }
        }
    }

    @Test
    fun `a keystore that cannot be read never gets an agent wallet built`() = runBlocking {
        // On the JVM there is no AndroidKeyStore at all: the factory must refuse, not build the
        // SDK, whose constructor creates a key whenever it cannot read one.
        val outcome = SubWalletFactory.get(android.content.ContextWrapper(null), 1) { null }
        assertTrue(outcome.toString(), outcome is SubWalletFactory.Outcome.Unavailable)
        assertTrue((outcome as SubWalletFactory.Outcome.Unavailable).reason.contains("Not creating a new one"))
    }
}
