package org.ethereumphone.andyclaw.agentwallet

/**
 * Outcome of a `WalletSDK.sendTransaction` call on the user's own dGEN1 wallet.
 *
 * Like the sub-account SDK (see [SubWalletResult]), WalletSDK does not throw when the bundler
 * rejects a UserOperation: it returns `"Error: <message>"`. The user-wallet tools checked only
 * for `"decline"`, so a send the user had approved on the terminal screen and the bundler then
 * refused came back as `status: "submitted"`, with the error text as its hash and nothing
 * on-chain.
 */
sealed interface UserWalletResult {

    data class Submitted(val userOpHash: String) : UserWalletResult

    /** The user said no on the terminal screen. */
    data object Declined : UserWalletResult

    data class Failed(val message: String) : UserWalletResult

    companion object {
        private val USER_OP_HASH = Regex("^0x[0-9a-fA-F]{64}$")

        /** `WalletSDK.DECLINE`. */
        private const val DECLINE = "decline"

        fun parse(raw: String?): UserWalletResult {
            val trimmed = raw?.trim().orEmpty()
            return when {
                trimmed == DECLINE -> Declined
                // A userOpHash is 32 bytes of hex; anything else merely starting with "0x"
                // is not proof that something was submitted.
                USER_OP_HASH.matches(trimmed) -> Submitted(trimmed)
                trimmed.isEmpty() -> Failed("The wallet returned an empty response.")
                else -> Failed(
                    trimmed.removePrefix("Error:").trim().ifEmpty { "The bundler rejected the transaction." }
                )
            }
        }
    }
}
