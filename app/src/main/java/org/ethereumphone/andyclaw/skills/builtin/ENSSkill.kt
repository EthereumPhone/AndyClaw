package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.kethereum.eip137.model.ENSName
import org.kethereum.ens.ENS
import org.kethereum.ens.isPotentialENSDomain
import org.kethereum.model.Address
import org.kethereum.rpc.HttpEthereumRPC

class ENSSkill : AndyClawSkill {
    override val id = "ens"
    override val name = "ENS Resolution"

    companion object {
        private const val TAG = "ENSSkill"
        private const val MAINNET_RPC = "https://ethereum.publicnode.com"
        private const val SEPOLIA_RPC = "https://ethereum-sepolia.publicnode.com"
    }

    override val baseManifest = SkillManifest(
        description = "Resolve Ethereum Name Service (ENS) names. " +
                "Converts ENS names (e.g. vitalik.eth) to addresses and addresses to ENS names.",
        tools = listOf(
            ToolDefinition(
                name = "resolve_ens",
                description = "Resolve an ENS name to an Ethereum address (forward resolution) " +
                        "or an Ethereum address to an ENS name (reverse resolution). " +
                        "Auto-detects direction: pass a .eth name for forward, or a 0x address for reverse. " +
                        "ENS resolution always uses Ethereum mainnet regardless of the chain_id parameter.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "input" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "An ENS name (e.g. 'vitalik.eth') for forward resolution, " +
                                                    "or an Ethereum address (0x...) for reverse resolution"
                                        ),
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("input"))),
                    )
                ),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "resolve_ens" -> resolveEns(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private suspend fun resolveEns(params: JsonObject): SkillResult {
        val input = params["input"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return SkillResult.Error("'input' parameter is required")

        return withContext(Dispatchers.IO) {
            try {
                val ens = ENS(HttpEthereumRPC(MAINNET_RPC))

                if (input.endsWith(".eth", ignoreCase = true)) {
                    resolveForward(ens, input)
                } else if (input.startsWith("0x") && input.length == 42) {
                    resolveReverse(ens, input)
                } else {
                    SkillResult.Error(
                        "Invalid input: must be an ENS name (ending with .eth) " +
                                "or an Ethereum address (starting with 0x, 42 characters)"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "ENS resolution failed", e)
                SkillResult.Error("ENS resolution failed: ${e.message}")
            }
        }
    }

    private fun resolveForward(ens: ENS, ensName: String): SkillResult {
        val name = ensName.lowercase()
        if (!ENSName(name).isPotentialENSDomain()) {
            return SkillResult.Error("Invalid ENS name format: $ensName")
        }

        val address = try {
            ens.getAddress(ENSName(name))
        } catch (e: Exception) {
            Log.e(TAG, "Forward resolution failed for $ensName", e)
            return SkillResult.Error("Failed to resolve ENS name '$ensName': ${e.message}")
        }

        return if (address != null) {
            Log.d(TAG, "Resolved $ensName -> ${address.hex}")
            SkillResult.Success("ENS name '$ensName' resolves to address: ${address.hex}")
        } else {
            SkillResult.Error("ENS name '$ensName' could not be resolved — name not found")
        }
    }

    private fun resolveReverse(ens: ENS, ethereumAddress: String): SkillResult {
        val ensName = try {
            ens.reverseResolve(Address(ethereumAddress))
        } catch (e: Exception) {
            Log.e(TAG, "Reverse resolution failed for $ethereumAddress", e)
            return SkillResult.Error("Failed to reverse resolve address '$ethereumAddress': ${e.message}")
        }

        return if (ensName != null) {
            Log.d(TAG, "Reverse resolved $ethereumAddress -> $ensName")
            SkillResult.Success("Address '$ethereumAddress' resolves to ENS name: $ensName")
        } else {
            SkillResult.Success("No ENS name found for address: $ethereumAddress")
        }
    }
}
