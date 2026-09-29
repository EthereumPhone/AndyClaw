package org.ethereumphone.andyclaw.safety

import org.ethereumphone.andyclaw.skills.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ToolEffects.USER_WALLET_TOOLS` is exactly the tools that act through the user's own wallet —
 * WalletSDK's `sendTransaction` / `signMessage`, confirmed by SystemUI on the terminal screen. A
 * home-screen turn runs these without a card, so a tool that lands here by mistake loses its
 * approval, and a new user-wallet tool left out of it gets a second dialog.
 */
class UserWalletToolsTest {

    private val skillsDir = File("src/main/java/org/ethereumphone/andyclaw/skills/builtin")
    private val toolName = Regex("""(?m)^\s*name = "([a-z0-9_]+)"""")

    /** Skills that send or sign through the OS wallet, not only read its address. */
    private fun userWalletSkills(): List<File> =
        skillsDir.listFiles { f -> f.extension == "kt" }.orEmpty().filter { f ->
            val src = f.readText()
            src.contains("import org.ethereumphone.walletsdk.WalletSDK") &&
                (src.contains(".sendTransaction(") || src.contains(".signMessage("))
        }

    @Test
    fun `the set is exactly the user's wallet tools`() {
        assertEquals(
            setOf(
                "propose_transaction", "propose_token_transfer", "send_native_token", "send_token",
                "swap_tokens", "create_bankr_order", "cancel_bankr_order",
            ),
            ToolEffects.USER_WALLET_TOOLS,
        )
    }

    @Test
    fun `every acting tool of a skill that uses the OS wallet is in it, and nothing else is`() {
        val skills = userWalletSkills()
        assertEquals(
            setOf("BankrTradingSkill.kt", "SwapSkill.kt", "WalletSkill.kt"),
            skills.map { it.name }.toSet(),
        )
        val acting = skills.flatMap { f -> toolName.findAll(f.readText()).map { it.groupValues[1] }.toList() }
            // The agent's own sub-account signs with no prompt: never the user's wallet.
            .filterNot { it.startsWith("agent_") }
            .filter { ToolEffects.of(it, null) != ToolEffect.READ }
            .toSet()
        assertEquals(acting, ToolEffects.USER_WALLET_TOOLS)
    }

    @Test
    fun `none of it is the agent's own wallet, and all of it is classified as acting`() {
        for (tool in ToolEffects.USER_WALLET_TOOLS) {
            assertFalse(tool, tool.startsWith("agent_"))
            assertTrue(tool, ToolEffects.isClassified(tool, null))
            assertTrue(tool, ToolEffects.of(tool, null) != ToolEffect.READ)
        }
    }
}
