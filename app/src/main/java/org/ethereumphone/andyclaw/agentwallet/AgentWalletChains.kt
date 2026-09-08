package org.ethereumphone.andyclaw.agentwallet

import org.ethereumphone.andyclaw.BuildConfig

/**
 * Canonical chain, RPC/bundler and token configuration for the agent sub-account.
 *
 * This used to live in [org.ethereumphone.andyclaw.skills.builtin.WalletSkill]'s companion
 * object, while the Settings screen derived its own mainnet-only copy. Both now read from
 * here so the LLM tools and the user-facing send UI can never disagree about which chains
 * exist or which contract a symbol resolves to.
 */
object AgentWalletChains {

    /** Alchemy network names keyed by chain ID. */
    val CHAIN_NAMES = mapOf(
        1 to "eth-mainnet",
        10 to "opt-mainnet",
        137 to "polygon-mainnet",
        42161 to "arb-mainnet",
        8453 to "base-mainnet",
        7777777 to "zora-mainnet",
        56 to "bnb-mainnet",
        43114 to "avax-mainnet",
    )

    /** Human-readable chain names for the UI. */
    val CHAIN_DISPLAY_NAMES = mapOf(
        1 to "Ethereum",
        10 to "Optimism",
        137 to "Polygon",
        42161 to "Arbitrum",
        8453 to "Base",
        7777777 to "Zora",
        56 to "BNB Chain",
        43114 to "Avalanche",
    )

    /**
     * Chains the send UI offers, cheapest-first so the default lands somewhere
     * a user can actually afford to move funds from.
     */
    val SUPPORTED_CHAIN_IDS = listOf(8453, 10, 42161, 137, 1, 56, 43114, 7777777)

    fun chainDisplayName(chainId: Int): String =
        CHAIN_DISPLAY_NAMES[chainId] ?: "Chain $chainId"

    fun chainIdToRpc(chainId: Int): String? {
        val name = CHAIN_NAMES[chainId] ?: return null
        return "https://${name}.g.alchemy.com/v2/${BuildConfig.ALCHEMY_API}"
    }

    fun chainIdToBundler(chainId: Int): String {
        return "https://api.pimlico.io/v2/$chainId/rpc?apikey=${BuildConfig.BUNDLER_API}"
    }

    /**
     * Chains where Alchemy serves `alchemy_getTokenBalances`. Everywhere else the
     * balance scan falls back to iterating [WELL_KNOWN_TOKENS] with `balanceOf`.
     */
    val ALCHEMY_TOKEN_BALANCE_CHAIN_IDS = setOf(1, 10, 137, 42161, 8453)

    // ── Native token info per chain ──────────────────────────────────

    data class NativeTokenInfo(
        val symbol: String,
        val name: String,
        val decimals: Int = 18,
    )

    val NATIVE_TOKENS = mapOf(
        1 to NativeTokenInfo("ETH", "Ether"),
        10 to NativeTokenInfo("ETH", "Ether"),
        137 to NativeTokenInfo("POL", "POL (ex-MATIC)"),
        42161 to NativeTokenInfo("ETH", "Ether"),
        8453 to NativeTokenInfo("ETH", "Ether"),
        7777777 to NativeTokenInfo("ETH", "Ether"),
        56 to NativeTokenInfo("BNB", "BNB"),
        43114 to NativeTokenInfo("AVAX", "Avalanche"),
    )

    fun nativeSymbol(chainId: Int): String = NATIVE_TOKENS[chainId]?.symbol ?: "ETH"

    // ── Well-known ERC-20 tokens ────────────────────────────────────

    data class WellKnownToken(
        val symbol: String,
        val name: String,
        val decimals: Int,
        /** chainId -> contract address (checksummed). */
        val addresses: Map<Int, String>,
    )

    /**
     * Registry of well-known tokens with verified contract addresses.
     * The LLM can look these up by symbol instead of needing to call
     * get_owned_tokens first.
     */
    val WELL_KNOWN_TOKENS = listOf(
        WellKnownToken(
            symbol = "USDC", name = "USD Coin", decimals = 6,
            addresses = mapOf(
                1 to "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                10 to "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85",
                137 to "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359",
                42161 to "0xaf88d065e77c8cC2239327C5EDb3A432268e5831",
                8453 to "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                43114 to "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E",
            ),
        ),
        WellKnownToken(
            symbol = "USDT", name = "Tether USD", decimals = 6,
            addresses = mapOf(
                1 to "0xdAC17F958D2ee523a2206206994597C13D831ec7",
                10 to "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58",
                137 to "0xc2132D05D31c914a87C6611C10748AEb04B58e8F",
                42161 to "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9",
                43114 to "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7",
            ),
        ),
        // Note: BSC USDT uses 18 decimals, listed separately
        WellKnownToken(
            symbol = "USDT", name = "Tether USD (BSC)", decimals = 18,
            addresses = mapOf(
                56 to "0x55d398326f99059fF775485246999027B3197955",
            ),
        ),
        WellKnownToken(
            symbol = "WETH", name = "Wrapped Ether", decimals = 18,
            addresses = mapOf(
                1 to "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2",
                10 to "0x4200000000000000000000000000000000000006",
                137 to "0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619",
                42161 to "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1",
                8453 to "0x4200000000000000000000000000000000000006",
            ),
        ),
        WellKnownToken(
            symbol = "DAI", name = "Dai Stablecoin", decimals = 18,
            addresses = mapOf(
                1 to "0x6B175474E89094C44Da98b954EedeAC495271d0F",
                10 to "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1",
                137 to "0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063",
                42161 to "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1",
                8453 to "0x50c5725949A6F0c72E6C4a641F24049A917DB0Cb",
            ),
        ),
        WellKnownToken(
            symbol = "WBTC", name = "Wrapped Bitcoin", decimals = 8,
            addresses = mapOf(
                1 to "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599",
                10 to "0x68f180fcCe6836688e9084f035309E29Bf0A2095",
                137 to "0x1BFD67037B42Cf73acF2047067bd4F2C47D9BfD6",
                42161 to "0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f",
            ),
        ),
        WellKnownToken(
            symbol = "LINK", name = "Chainlink", decimals = 18,
            addresses = mapOf(
                1 to "0x514910771AF9Ca656af840dff83E8264EcF986CA",
                10 to "0x350a791Bfc2C21F9Ed5d10980Dad2e2638ffa7f6",
                137 to "0x53E0bca35eC356BD5ddDFebbD1Fc0fD03FaBad39",
                42161 to "0xf97f4df75117a78c1A5a0DBb814Af92458539FB4",
                8453 to "0x88Fb150BDc53A65fe94Dea0c9BA0a6dAf8C6e196",
            ),
        ),
        WellKnownToken(
            symbol = "UNI", name = "Uniswap", decimals = 18,
            addresses = mapOf(
                1 to "0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984",
                10 to "0x6fd9d7AD17242c41f7131d257212c54A0e816691",
                137 to "0xb33EaAd8d922B1083446DC23f610c2567fB5180f",
                42161 to "0xFa7F8980b0f1E64A2062791cc3b0871572f1F7f0",
            ),
        ),
        WellKnownToken(
            symbol = "DEGEN", name = "Degen", decimals = 18,
            addresses = mapOf(
                8453 to "0x4ed4E862860beD51a9570b96d89aF5E1B0Efefed",
            ),
        ),
        WellKnownToken(
            symbol = "AAVE", name = "Aave", decimals = 18,
            addresses = mapOf(
                1 to "0x7Fc66500c84A76Ad7e9c93437bFc5Ac33E2DDaE9",
                10 to "0x76FB31fb4af56892A25e32cFC43De717950c9278",
                137 to "0xD6DF932A45C0f255f85145f286eA0b292B21C90B",
                42161 to "0xba5DdD1f9d7F570dc94a51479a000E3BCE967196",
            ),
        ),
    )

    /**
     * Find a well-known token by symbol and chain.
     * Returns (contractAddress, decimals) or null.
     */
    fun resolveWellKnownToken(symbol: String, chainId: Int): Pair<String, Int>? {
        val upper = symbol.uppercase()
        for (token in WELL_KNOWN_TOKENS) {
            if (token.symbol.uppercase() == upper) {
                val address = token.addresses[chainId] ?: continue
                return address to token.decimals
            }
        }
        return null
    }

    /**
     * Find all chain deployments for a symbol.
     */
    fun findTokenBySymbol(symbol: String): List<WellKnownToken> {
        val upper = symbol.uppercase()
        return WELL_KNOWN_TOKENS.filter { it.symbol.uppercase() == upper }
    }

    /** Reverse lookup: known decimals for a contract address on a chain, or null. */
    fun resolveDecimalsByAddress(contractAddress: String, chainId: Int): Int? {
        val lower = contractAddress.lowercase()
        for (token in WELL_KNOWN_TOKENS) {
            if (token.addresses[chainId]?.lowercase() == lower) return token.decimals
        }
        return null
    }

    /** Every well-known token deployed on [chainId]. */
    fun tokensOnChain(chainId: Int): List<WellKnownToken> =
        WELL_KNOWN_TOKENS.filter { it.addresses.containsKey(chainId) }
}
