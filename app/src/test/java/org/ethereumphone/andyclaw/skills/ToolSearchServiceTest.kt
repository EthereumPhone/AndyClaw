package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolSearchServiceTest {

    // ── Test fixtures ────────────────────────────────────────────────

    private val CORE_IDS = setOf("code_execution", "memory")

    /** Minimal ToolDefinition builder. */
    private fun tool(name: String, description: String) = ToolDefinition(
        name = name,
        description = description,
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    )

    /** Minimal skill builder. */
    private fun skill(id: String, name: String, vararg tools: ToolDefinition) = object : AndyClawSkill {
        override val id = id
        override val name = name
        override val baseManifest = SkillManifest(
            description = "$name skill",
            tools = tools.toList(),
        )
        override val privilegedManifest: SkillManifest? = null
        override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
            return SkillResult.Success("ok")
        }
    }

    private lateinit var registry: NativeSkillRegistry
    private lateinit var allSkillIds: Set<String>

    @Before
    fun setup() {
        registry = NativeSkillRegistry()
        // Register CORE skills
        registry.register(skill("code_execution", "Code Execution",
            tool("execute_code", "Execute Java/BeanShell code on the device"),
        ))
        registry.register(skill("memory", "Memory",
            tool("store_memory", "Store a memory for later recall"),
            tool("recall_memory", "Recall a stored memory"),
        ))
        // Register discoverable skills
        registry.register(skill("sms", "SMS",
            tool("send_sms", "Send an SMS text message to a phone number"),
            tool("read_sms", "Read recent SMS messages"),
        ))
        registry.register(skill("wallet", "Wallet",
            tool("send_eth", "Send ETH to an address"),
            tool("get_balance", "Get the wallet ETH balance"),
            tool("get_user_wallet_address", "Get the user's wallet address"),
        ))
        registry.register(skill("ens", "ENS",
            tool("resolve_ens", "Resolve an ENS name to an Ethereum address"),
        ))
        registry.register(skill("connectivity", "Connectivity",
            tool("toggle_wifi", "Turn WiFi on or off"),
            tool("toggle_bluetooth", "Turn Bluetooth on or off"),
        ))
        registry.register(skill("weather", "Weather",
            tool("get_weather", "Get current weather for a location"),
        ))
        registry.register(skill("contacts", "Contacts",
            tool("search_contacts", "Search contacts by name"),
            tool("get_eth_contacts", "Get contacts with ETH addresses"),
        ))

        allSkillIds = registry.getAll().map { it.id }.toSet()
    }

    private fun createService(
        tier: Tier = Tier.OPEN,
        enabledSkillIds: Set<String> = allSkillIds,
    ) = ToolSearchService(
        skillRegistry = registry,
        tier = tier,
        enabledSkillIds = enabledSkillIds,
    )

    // ── Search tests ─────────────────────────────────────────────────

    @Test
    fun `search finds tools by name keyword`() {
        val service = createService()
        val results = service.search("sms")
        assertTrue("Should find send_sms", results.any { it.toolName == "send_sms" })
        assertTrue("Should find read_sms", results.any { it.toolName == "read_sms" })
    }

    @Test
    fun `search finds tools by description keyword`() {
        val service = createService()
        val results = service.search("ethereum address")
        assertTrue("Should find resolve_ens", results.any { it.toolName == "resolve_ens" })
    }

    @Test
    fun `search finds wallet tools`() {
        val service = createService()
        val results = service.search("send ETH wallet balance")
        assertTrue("Should find send_eth", results.any { it.toolName == "send_eth" })
        assertTrue("Should find get_balance", results.any { it.toolName == "get_balance" })
    }

    @Test
    fun `search returns empty for unrelated query`() {
        val service = createService()
        val results = service.search("xyzzy foobar nonexistent")
        assertTrue("Should return empty for nonsense query", results.isEmpty())
    }

    @Test
    fun `search returns max 5 results`() {
        val service = createService()
        // Broad query that could match many tools
        val results = service.search("get")
        assertTrue("Should return at most 5 results", results.size <= 5)
    }

    @Test
    fun `search does not include CORE skill tools`() {
        val service = createService()
        val results = service.search("execute code")
        // execute_code is a CORE tool, should not appear in search results
        assertFalse("CORE tool execute_code should not be in search results",
            results.any { it.toolName == "execute_code" })
    }

    @Test
    fun `empty query returns no results`() {
        val service = createService()
        val results = service.search("")
        assertTrue("Empty query should return no results", results.isEmpty())
    }

    // ── Discovered tools tracking ────────────────────────────────────

    @Test
    fun `executeSearch tracks discovered tools`() {
        val service = createService()
        val params = buildJsonObject { put("query", "wifi bluetooth") }
        service.executeSearch(params)

        val discovered = service.getDiscoveredToolNames()
        assertTrue("toggle_wifi should be discovered", "toggle_wifi" in discovered)
        assertTrue("toggle_bluetooth should be discovered", "toggle_bluetooth" in discovered)
    }

    @Test
    fun `discovered tools persist across multiple searches`() {
        val service = createService()
        service.executeSearch(buildJsonObject { put("query", "sms message") })
        service.executeSearch(buildJsonObject { put("query", "wifi") })

        val discovered = service.getDiscoveredToolNames()
        assertTrue("send_sms should persist", "send_sms" in discovered)
        assertTrue("toggle_wifi should also be present", "toggle_wifi" in discovered)
    }

    @Test
    fun `resetSession clears discovered tools`() {
        val service = createService()
        service.executeSearch(buildJsonObject { put("query", "sms") })
        assertTrue(service.getDiscoveredToolNames().isNotEmpty())

        service.resetSession()
        assertTrue("Should be empty after reset", service.getDiscoveredToolNames().isEmpty())
    }

    // ── Tool list building ───────────────────────────────────────────

    @Test
    fun `buildToolList includes CORE tools by default`() {
        val service = createService()
        val tools = service.buildToolList()
        val toolNames = tools.map { it["name"].toString().trim('"') }

        assertTrue("Should include execute_code (CORE)", "execute_code" in toolNames)
        assertTrue("Should include store_memory (CORE)", "store_memory" in toolNames)
        assertTrue("Should include recall_memory (CORE)", "recall_memory" in toolNames)
    }

    @Test
    fun `buildToolList includes search meta-tool`() {
        val service = createService()
        val tools = service.buildToolList()
        val toolNames = tools.map { it["name"].toString().trim('"') }

        assertTrue("Should include search_available_tools",
            ToolSearchService.TOOL_NAME in toolNames)
    }

    @Test
    fun `buildToolList does not include non-discovered non-CORE tools`() {
        val service = createService()
        val tools = service.buildToolList()
        val toolNames = tools.map { it["name"].toString().trim('"') }

        assertFalse("send_sms should not be present before discovery", "send_sms" in toolNames)
        assertFalse("resolve_ens should not be present before discovery", "resolve_ens" in toolNames)
    }

    @Test
    fun `buildToolList includes discovered tools after search`() {
        val service = createService()
        service.executeSearch(buildJsonObject { put("query", "ENS resolve") })

        val tools = service.buildToolList()
        val toolNames = tools.map { it["name"].toString().trim('"') }

        assertTrue("resolve_ens should be present after discovery", "resolve_ens" in toolNames)
    }

    // ── Multi-turn scenario (the "yes" problem) ──────────────────────

    @Test
    fun `discovered tools persist across turns - ENS scenario`() {
        val service = createService()

        // Turn 1: user asks about ENS
        service.executeSearch(buildJsonObject { put("query", "ENS name resolve") })
        val turn1Tools = service.buildToolList()
        val turn1Names = turn1Tools.map { it["name"].toString().trim('"') }
        assertTrue("Turn 1: resolve_ens available", "resolve_ens" in turn1Names)

        // Turn 2: user says "now mhaas.eth" - no new search needed
        // The tools from turn 1 are still in the discovered set
        val turn2Tools = service.buildToolList()
        val turn2Names = turn2Tools.map { it["name"].toString().trim('"') }
        assertTrue("Turn 2: resolve_ens still available", "resolve_ens" in turn2Names)
    }

    @Test
    fun `discovered tools persist across turns - yes scenario`() {
        val service = createService()

        // Turn 1: user asks about weather
        service.executeSearch(buildJsonObject { put("query", "weather forecast") })
        assertTrue("get_weather discovered", "get_weather" in service.getDiscoveredToolNames())

        // Turn 2: user says "yes" — no new search, but tool still available
        val tools = service.buildToolList()
        val names = tools.map { it["name"].toString().trim('"') }
        assertTrue("get_weather still available for 'yes' follow-up", "get_weather" in names)
    }

    // ── Catalog summary ──────────────────────────────────────────────

    @Test
    fun `catalog summary lists skill categories`() {
        val service = createService()
        val summary = service.buildCatalogSummary()

        assertTrue("Should mention SMS", summary.contains("SMS"))
        assertTrue("Should mention Wallet", summary.contains("Wallet"))
        assertTrue("Should mention ENS", summary.contains("ENS"))
        assertTrue("Should mention search_available_tools", summary.contains("search_available_tools"))
    }

    @Test
    fun `catalog summary does not list CORE skills`() {
        val service = createService()
        val summary = service.buildCatalogSummary()

        // CORE skills should not be listed as searchable categories
        assertFalse("Should not list Code Execution as searchable",
            summary.contains("Code Execution"))
    }

    // ── addDiscoveredTools ───────────────────────────────────────────

    @Test
    fun `addDiscoveredTools pre-seeds tools`() {
        val service = createService()
        service.addDiscoveredTools(setOf("send_sms", "resolve_ens"))

        val tools = service.buildToolList()
        val names = tools.map { it["name"].toString().trim('"') }
        assertTrue("Pre-seeded send_sms should be present", "send_sms" in names)
        assertTrue("Pre-seeded resolve_ens should be present", "resolve_ens" in names)
    }

    // ── Prefetch ─────────────────────────────────────────────────────

    @Test
    fun `prefetch loads the tools a request names before any model call`() {
        val service = createService()
        val loaded = service.prefetch("Please turn on my wifi")
        assertEquals("toggle_wifi", loaded.first())
        assertTrue("toggle_wifi" in service.getDiscoveredToolNames())
        // The model is told, so it does not search for what it already has.
        assertTrue(service.buildCatalogSummary().contains("Already loaded, call directly without searching: "))
    }

    @Test
    fun `a request of nothing but function words loads nothing`() {
        val service = createService()
        assertEquals(emptyList<String>(), service.prefetch("Can you please turn it on for me?"))
        assertTrue(service.getDiscoveredToolNames().isEmpty())
    }

    @Test
    fun `settings writers answer to what they control`() {
        // agentbench: "Turn on dark mode" never ranked write_secure_setting, whose hint said only
        // "change android secure preference", and cost a search round trip every time.
        registry.register(skill("settings", "Settings",
            tool("write_system_setting", "Write a system setting"),
            tool("write_secure_setting", "Write a secure setting"),
            tool("write_global_setting", "Write a global setting"),
        ))
        val service = createService(enabledSkillIds = registry.getAll().map { it.id }.toSet())
        assertEquals("write_secure_setting", service.prefetch("Turn on dark mode.").first())
        assertEquals("write_system_setting", service.prefetch("Set the font size to the largest option").first())
        assertEquals("write_global_setting", service.prefetch("Keep the screen on while charging").first())
    }
}
