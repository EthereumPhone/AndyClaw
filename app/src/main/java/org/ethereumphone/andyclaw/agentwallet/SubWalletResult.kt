package org.ethereumphone.andyclaw.agentwallet

/**
 * Outcome of a `SubWalletSDK.sendTransaction` call.
 *
 * The SDK does **not** throw when a send fails. It returns whatever the bundler said, and
 * `parseRpcResult` turns a JSON-RPC error into the plain string `"Error: <message>"`. Every
 * agent send path except `agentSwap` treated any returned string as success, so a failed
 * send was reported to the user as `status: "submitted"` and the error text was written to
 * the transaction history as if it were a userOpHash.
 *
 * Everything that calls the SDK goes through [parse] instead.
 */
sealed interface SubWalletResult {

    data class Success(val userOpHash: String) : SubWalletResult

    data class Failure(val message: String, val raw: String) : SubWalletResult

    companion object {

        /**
         * Interpret a raw SDK return value.
         *
         * @param nativeSymbol gas token of the chain the send was attempted on, used to
         *   turn the opaque ERC-4337 `AA21` code into something a user can act on.
         * @param chainName human-readable chain name, same purpose.
         */
        fun parse(raw: String, nativeSymbol: String, chainName: String): SubWalletResult {
            val trimmed = raw.trim()

            // A userOpHash is 32 bytes of hex. Anything else that merely starts with "0x"
            // is not something we should record as one.
            if (trimmed.startsWith("0x") && trimmed.length == 66 && isHex(trimmed)) {
                return Success(trimmed)
            }

            return Failure(explain(trimmed, nativeSymbol, chainName), trimmed)
        }

        private fun isHex(value: String): Boolean =
            value.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        private fun explain(raw: String, nativeSymbol: String, chainName: String): String = when {
            // The account could not pay its own prefund. With no paymaster, this is by far
            // the most common failure and the least self-explanatory.
            raw.contains("AA21") ->
                "Not enough $nativeSymbol in the agent wallet on $chainName to pay gas."

            raw.contains("AA13") || raw.contains("initCode") ->
                "The agent wallet could not be deployed on $chainName. Check that it holds " +
                    "$nativeSymbol for the deployment gas."

            raw.contains("AA25") || raw.contains("nonce", ignoreCase = true) ->
                "Nonce conflict — another agent wallet transaction may still be pending. " +
                    "Wait for it to confirm and try again."

            raw.contains("replacement underpriced", ignoreCase = true) ->
                "A previous transaction from the agent wallet is still pending on $chainName."

            raw.isEmpty() -> "The bundler returned an empty response."

            raw.startsWith("Error:") -> raw.removePrefix("Error:").trim().ifEmpty {
                "The bundler rejected the transaction."
            }

            else -> raw
        }
    }
}
