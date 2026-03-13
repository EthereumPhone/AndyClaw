package org.ethereumphone.andyclaw.skills

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SkillRouterTest {

    // ── Helper ──────────────────────────────────────────────────────────

    /** All built-in skill IDs that could be enabled. */
    private val ALL_SKILL_IDS = setOf(
        // CORE
        "device_info", "apps", "code_execution", "shell", "memory", "clipboard", "settings",
        // dGEN1 CORE
        "led_matrix", "terminal_text",
        // HEAVY
        "agent_display", "skill-creator", "skill-refinement", "clawhub", "bankr_trading", "cli-tool-manager",
        // STANDARD
        "connectivity", "phone", "sms", "contacts", "calendar", "google_calendar",
        "notifications", "location", "audio", "screen", "camera", "storage", "filesystem",
        "device_power", "reminders", "cronjobs", "web_search", "wallet", "swap",
        "token_lookup", "ens", "gmail", "drive", "sheets", "telegram", "messenger",
        "aurora_store", "package_manager", "termux", "screen_time", "proactive_agent",
    )

    private val CORE_IDS = setOf(
        "device_info", "apps", "code_execution", "shell", "memory", "clipboard", "settings",
    )

    private val DGEN1_CORE_IDS = setOf("led_matrix", "terminal_text", "agent_display")

    /** Create a SkillRouter with no context/registry/embedding (keyword-only mode). */
    private fun router() = SkillRouter()

    /** Shortcut for calling routeSkills in tests (wraps in runBlocking). */
    private fun route(
        msg: String,
        enabled: Set<String> = ALL_SKILL_IDS,
        tier: Tier = Tier.OPEN,
        previousToolNames: Set<String> = emptySet(),
        router: SkillRouter = router(),
    ): Set<String> = runBlocking {
        router.routeSkills(msg, enabled, tier, previousToolNames)
    }

    // ── CORE skills always included ─────────────────────────────────────

    @Test
    fun `core skills always included when keyword matches something`() {
        val result = route("turn on wifi")
        for (id in CORE_IDS) {
            assertTrue("CORE skill '$id' should be present", id in result)
        }
    }

    @Test
    fun `core skills always included on privileged tier`() {
        val result = route("turn on wifi", tier = Tier.PRIVILEGED)
        for (id in CORE_IDS) {
            assertTrue("CORE skill '$id' should be present", id in result)
        }
    }

    @Test
    fun `core skills only included if enabled`() {
        val partialEnabled = setOf("device_info", "connectivity")
        val result = route("turn on wifi", enabled = partialEnabled)
        assertTrue("device_info" in result)
        assertTrue("connectivity" in result)
        assertFalse("apps" in result)
        assertFalse("shell" in result)
    }

    // ── dGEN1 CORE (PRIVILEGED tier) ────────────────────────────────────

    @Test
    fun `led_matrix always included on dGEN1 privileged tier`() {
        val result = route("turn on wifi", tier = Tier.PRIVILEGED)
        assertTrue("led_matrix should be included on dGEN1", "led_matrix" in result)
    }

    @Test
    fun `terminal_text always included on dGEN1 privileged tier`() {
        val result = route("turn on wifi", tier = Tier.PRIVILEGED)
        assertTrue("terminal_text should be included on dGEN1", "terminal_text" in result)
    }

    @Test
    fun `dGEN1 core skills NOT included on OPEN tier`() {
        val result = route("turn on wifi")
        assertFalse("led_matrix should NOT be included on OPEN tier", "led_matrix" in result)
        assertFalse("terminal_text should NOT be included on OPEN tier", "terminal_text" in result)
    }

    @Test
    fun `dGEN1 core skills not included if user disabled them`() {
        val enabledWithoutHardware = ALL_SKILL_IDS - DGEN1_CORE_IDS
        val result = route("turn on wifi", enabled = enabledWithoutHardware, tier = Tier.PRIVILEGED)
        assertFalse("led_matrix should not be included if disabled", "led_matrix" in result)
        assertFalse("terminal_text should not be included if disabled", "terminal_text" in result)
    }

    @Test
    fun `dGEN1 core skills included even with no keyword match besides fallback`() {
        val result = route("what is the weather like today?", tier = Tier.PRIVILEGED)
        assertTrue("led_matrix" in result)
        assertTrue("terminal_text" in result)
    }

    @Test
    fun `dGEN1 core skills present alongside matched skills`() {
        val result = route("send an sms", tier = Tier.PRIVILEGED)
        assertTrue("sms should match", "sms" in result)
        assertTrue("led_matrix should always be present on dGEN1", "led_matrix" in result)
        assertTrue("terminal_text should always be present on dGEN1", "terminal_text" in result)
    }

    // ── Keyword matching: connectivity ──────────────────────────────────

    @Test
    fun `wifi keyword routes to connectivity skill`() {
        val result = route("turn on wifi")
        assertTrue("connectivity" in result)
    }

    @Test
    fun `bluetooth keyword routes to connectivity`() {
        val result = route("enable bluetooth")
        assertTrue("connectivity" in result)
    }

    @Test
    fun `hotspot keyword routes to connectivity`() {
        val result = route("start the hotspot")
        assertTrue("connectivity" in result)
    }

    @Test
    fun `airplane keyword routes to connectivity`() {
        val result = route("turn on airplane mode")
        assertTrue("connectivity" in result)
    }

    @Test
    fun `network keyword routes to connectivity`() {
        val result = route("check network status")
        assertTrue("connectivity" in result)
    }

    // ── Keyword matching: phone / SMS ───────────────────────────────────

    @Test
    fun `call keyword routes to phone`() {
        val result = route("call mom")
        assertTrue("phone" in result)
    }

    @Test
    fun `dial keyword routes to phone`() {
        val result = route("dial 911")
        assertTrue("phone" in result)
    }

    @Test
    fun `sms keyword routes to sms`() {
        val result = route("send an sms to John")
        assertTrue("sms" in result)
    }

    @Test
    fun `text message keyword routes to sms`() {
        val result = route("send a text message")
        assertTrue("sms" in result)
    }

    // ── Keyword matching: calendar ──────────────────────────────────────

    @Test
    fun `calendar keyword routes to calendar and google_calendar`() {
        val result = route("add a calendar event")
        assertTrue("calendar" in result)
        assertTrue("google_calendar" in result)
    }

    @Test
    fun `meeting keyword routes to calendar`() {
        val result = route("schedule a meeting tomorrow")
        assertTrue("calendar" in result)
    }

    // ── Keyword matching: audio / volume ────────────────────────────────

    @Test
    fun `volume keyword routes to audio`() {
        val result = route("turn up the volume")
        assertTrue("audio" in result)
    }

    @Test
    fun `mute keyword routes to audio`() {
        val result = route("mute the phone")
        assertTrue("audio" in result)
    }

    // ── Keyword matching: location ──────────────────────────────────────

    @Test
    fun `gps keyword routes to location`() {
        val result = route("turn on gps")
        assertTrue("location" in result)
    }

    @Test
    fun `where am i routes to location`() {
        val result = route("where am i right now?")
        assertTrue("location" in result)
    }

    // ── Keyword matching: device power ──────────────────────────────────

    @Test
    fun `battery keyword routes to device_power`() {
        val result = route("what is my battery level?")
        assertTrue("device_power" in result)
    }

    @Test
    fun `reboot keyword routes to device_power`() {
        val result = route("reboot the phone")
        assertTrue("device_power" in result)
    }

    // ── Keyword matching: web search ────────────────────────────────────

    @Test
    fun `search keyword routes to web_search`() {
        val result = route("search for the latest news")
        assertTrue("web_search" in result)
    }

    @Test
    fun `google keyword routes to web_search`() {
        val result = route("google this for me")
        assertTrue("web_search" in result)
    }

    // ── Keyword matching: reminders ─────────────────────────────────────

    @Test
    fun `remind keyword routes to reminders`() {
        val result = route("remind me at 5pm")
        assertTrue("reminders" in result)
    }

    @Test
    fun `alarm keyword routes to reminders`() {
        val result = route("set an alarm for 7am")
        assertTrue("reminders" in result)
    }

    // ── Keyword matching: contacts ──────────────────────────────────────

    @Test
    fun `contacts keyword routes to contacts`() {
        val result = route("show me my contacts")
        assertTrue("contacts" in result)
    }

    // ── Keyword matching: notifications ─────────────────────────────────

    @Test
    fun `notification keyword routes to notifications`() {
        val result = route("show my notifications")
        assertTrue("notifications" in result)
    }

    // ── Keyword matching: camera ────────────────────────────────────────

    @Test
    fun `photo keyword routes to camera`() {
        val result = route("take a photo")
        assertTrue("camera" in result)
    }

    // ── Keyword matching: storage / files ───────────────────────────────

    @Test
    fun `file keyword routes to storage and filesystem`() {
        val result = route("find a file on my phone")
        assertTrue("storage" in result)
        assertTrue("filesystem" in result)
    }

    @Test
    fun `download keyword routes to storage and filesystem`() {
        val result = route("check my downloads folder")
        assertTrue("storage" in result)
        assertTrue("filesystem" in result)
    }

    // ── Keyword matching: screen / brightness ───────────────────────────

    @Test
    fun `brightness keyword routes to screen`() {
        val result = route("set brightness to 50%")
        assertTrue("screen" in result)
    }

    // ── Keyword matching: cronjobs ──────────────────────────────────────

    @Test
    fun `cron keyword routes to cronjobs`() {
        val result = route("set up a cron job")
        assertTrue("cronjobs" in result)
    }

    // ── Keyword matching: HEAVY skills ──────────────────────────────────

    @Test
    fun `open app keyword routes to agent_display`() {
        val result = route("open app Settings")
        assertTrue("agent_display" in result)
    }

    @Test
    fun `launch app keyword routes to agent_display`() {
        val result = route("launch app Chrome")
        assertTrue("agent_display" in result)
    }

    @Test
    fun `screenshot keyword routes to agent_display and screen`() {
        val result = route("take a screenshot")
        assertTrue("agent_display" in result)
        assertTrue("screen" in result)
    }

    @Test
    fun `led keyword routes to led_matrix`() {
        val result = route("change the led color")
        assertTrue("led_matrix" in result)
    }

    @Test
    fun `terminal keyword routes to terminal_text`() {
        val result = route("update the terminal display")
        assertTrue("terminal_text" in result)
    }

    @Test
    fun `create skill keyword routes to skill-creator and skill-refinement`() {
        val result = route("create skill for weather")
        assertTrue("skill-creator" in result)
        assertTrue("skill-refinement" in result)
    }

    @Test
    fun `clawhub keyword routes to clawhub`() {
        val result = route("browse the clawhub marketplace")
        assertTrue("clawhub" in result)
    }

    @Test
    fun `trade keyword routes to bankr_trading`() {
        val result = route("trade some ETH for USDC")
        assertTrue("bankr_trading" in result)
    }

    @Test
    fun `cli tool keyword routes to cli-tool-manager`() {
        val result = route("install a cli tool")
        assertTrue("cli-tool-manager" in result)
    }

    // ── Keyword matching: wallet / crypto ───────────────────────────────

    @Test
    fun `wallet keyword routes to crypto skills`() {
        val result = route("check my wallet balance")
        assertTrue("wallet" in result)
        assertTrue("swap" in result)
        assertTrue("token_lookup" in result)
        assertTrue("ens" in result)
    }

    @Test
    fun `ethereum keyword routes to crypto skills`() {
        val result = route("send ethereum to vitalik.eth")
        assertTrue("wallet" in result)
        assertTrue("ens" in result)
    }

    // ── Keyword matching: email / drive / sheets ────────────────────────

    @Test
    fun `email keyword routes to gmail`() {
        val result = route("send an email")
        assertTrue("gmail" in result)
    }

    @Test
    fun `gmail keyword routes to gmail`() {
        val result = route("open gmail")
        assertTrue("gmail" in result)
    }

    @Test
    fun `google drive keyword routes to drive`() {
        val result = route("upload to google drive")
        assertTrue("drive" in result)
    }

    @Test
    fun `spreadsheet keyword routes to sheets`() {
        val result = route("open the spreadsheet")
        assertTrue("sheets" in result)
    }

    // ── Keyword matching: messaging ─────────────────────────────────────

    @Test
    fun `telegram keyword routes to telegram`() {
        val result = route("send a telegram message")
        assertTrue("telegram" in result)
    }

    @Test
    fun `messenger keyword routes to messenger`() {
        val result = route("open messenger")
        assertTrue("messenger" in result)
    }

    // ── Keyword matching: apps / packages ───────────────────────────────

    @Test
    fun `install app keyword routes to aurora_store`() {
        val result = route("install app Spotify")
        assertTrue("aurora_store" in result)
    }

    @Test
    fun `uninstall keyword routes to package_manager`() {
        val result = route("uninstall Facebook")
        assertTrue("package_manager" in result)
    }

    // ── Keyword matching: termux ────────────────────────────────────────

    @Test
    fun `termux keyword routes to termux`() {
        val result = route("run this in termux")
        assertTrue("termux" in result)
    }

    // ── Keyword matching: screen time ───────────────────────────────────

    @Test
    fun `screen time keyword routes to screen_time`() {
        val result = route("show my screen time")
        assertTrue("screen_time" in result)
    }

    @Test
    fun `app usage keyword routes to screen_time`() {
        val result = route("show app usage stats")
        assertTrue("screen_time" in result)
    }

    // ── Case insensitivity ──────────────────────────────────────────────

    @Test
    fun `matching is case insensitive`() {
        val lower = route("turn on wifi")
        val upper = route("Turn On WIFI")
        val mixed = route("Turn on WiFi")
        assertEquals(lower, upper)
        assertEquals(lower, mixed)
    }

    @Test
    fun `case insensitive for multi-word keywords`() {
        val result = route("Send a TEXT MESSAGE to Bob")
        assertTrue("sms" in result)
    }

    // ── Fallback behavior ───────────────────────────────────────────────

    @Test
    fun `fallback sends all skills when no keywords match`() {
        val result = route("hello how are you doing today?")
        assertEquals("Fallback should return all enabled skills", ALL_SKILL_IDS, result)
    }

    @Test
    fun `fallback returns exact set passed in when no match`() {
        val subset = setOf("device_info", "apps", "sms")
        val result = route("what is the meaning of life?", enabled = subset)
        assertEquals(subset, result)
    }

    @Test
    fun `fallback triggers for gibberish input`() {
        val result = route("asdfghjkl qwerty")
        assertEquals(ALL_SKILL_IDS, result)
    }

    @Test
    fun `fallback sends all on privileged tier too`() {
        val result = route("how are you?", tier = Tier.PRIVILEGED)
        assertEquals(ALL_SKILL_IDS, result)
    }

    // ── HEAVY skills excluded unless keyword matched ────────────────────

    @Test
    fun `agent_display NOT included without keyword on OPEN tier`() {
        val result = route("turn on wifi")
        assertFalse("agent_display should NOT be included for wifi query on OPEN", "agent_display" in result)
    }

    @Test
    fun `agent_display always included on PRIVILEGED tier even without keyword`() {
        val result = route("turn on wifi", tier = Tier.PRIVILEGED)
        assertTrue("agent_display should always be included on dGEN1", "agent_display" in result)
    }

    @Test
    fun `skill-creator NOT included without keyword`() {
        val result = route("turn on wifi")
        assertFalse("skill-creator" in result)
    }

    @Test
    fun `bankr_trading NOT included without keyword`() {
        val result = route("turn on wifi")
        assertFalse("bankr_trading" in result)
    }

    @Test
    fun `clawhub NOT included without keyword`() {
        val result = route("turn on wifi")
        assertFalse("clawhub" in result)
    }

    @Test
    fun `cli-tool-manager NOT included without keyword`() {
        val result = route("turn on wifi")
        assertFalse("cli-tool-manager" in result)
    }

    // ── Non-matched STANDARD skills excluded ────────────────────────────

    @Test
    fun `unrelated standard skills excluded when keyword matches`() {
        val result = route("turn on wifi")
        assertTrue("connectivity" in result)
        assertFalse("phone" in result)
        assertFalse("sms" in result)
        assertFalse("calendar" in result)
        assertFalse("gmail" in result)
        assertFalse("camera" in result)
        assertFalse("telegram" in result)
    }

    @Test
    fun `only sms included for text message query, not phone or calendar`() {
        val result = route("send an sms")
        assertTrue("sms" in result)
        assertFalse("calendar" in result)
        assertFalse("camera" in result)
        assertFalse("gmail" in result)
    }

    // ── Multi-keyword messages ──────────────────────────────────────────

    @Test
    fun `multiple keywords route to multiple skills`() {
        val result = route("turn on wifi and call mom")
        assertTrue("connectivity" in result)
        assertTrue("phone" in result)
    }

    @Test
    fun `complex message matches several skills`() {
        val result = route(
            "search for a restaurant, add it to my calendar, and send the details via sms",
        )
        assertTrue("web_search" in result)
        assertTrue("calendar" in result)
        assertTrue("sms" in result)
    }

    @Test
    fun `wallet and trading in same message`() {
        val result = route("check my wallet balance and trade some tokens")
        assertTrue("wallet" in result)
        assertTrue("bankr_trading" in result)
    }

    // ── External / dynamic skills ───────────────────────────────────────

    @Test
    fun `external clawhub skills always included`() {
        val enabled = ALL_SKILL_IDS + setOf("clawhub:weather-pro", "clawhub:fitness-tracker")
        val result = route("turn on wifi", enabled = enabled)
        assertTrue("clawhub:weather-pro" in result)
        assertTrue("clawhub:fitness-tracker" in result)
    }

    @Test
    fun `external ai skills always included`() {
        val enabled = ALL_SKILL_IDS + setOf("ai:my-custom-skill")
        val result = route("turn on wifi", enabled = enabled)
        assertTrue("ai:my-custom-skill" in result)
    }

    @Test
    fun `external ext skills always included`() {
        val enabled = ALL_SKILL_IDS + setOf("ext:third-party")
        val result = route("turn on wifi", enabled = enabled)
        assertTrue("ext:third-party" in result)
    }

    @Test
    fun `external skills included alongside matched skills`() {
        val enabled = ALL_SKILL_IDS + setOf("clawhub:photo-editor")
        val result = route("take a photo", enabled = enabled)
        assertTrue("camera" in result)
        assertTrue("clawhub:photo-editor" in result)
    }

    @Test
    fun `external skills NOT counted as keyword matches for fallback`() {
        val enabled = setOf("device_info", "clawhub:my-skill")
        val result = route("xyzzy nonsense", enabled = enabled)
        assertEquals(enabled, result)
    }

    // ── Disabled skills filtering ───────────────────────────────────────

    @Test
    fun `disabled skills not returned even if keywords match`() {
        val enabledWithoutConnectivity = ALL_SKILL_IDS - setOf("connectivity")
        val result = route("turn on wifi", enabled = enabledWithoutConnectivity)
        assertFalse("connectivity should NOT be returned if disabled", "connectivity" in result)
    }

    @Test
    fun `disabled core skills not returned`() {
        val enabledWithoutShell = ALL_SKILL_IDS - setOf("shell")
        val result = route("turn on wifi", enabled = enabledWithoutShell)
        assertFalse("shell should NOT be returned if disabled", "shell" in result)
    }

    @Test
    fun `empty enabled set returns empty on keyword match`() {
        val result = route("turn on wifi", enabled = emptySet())
        assertTrue(result.isEmpty())
    }

    // ── Token reduction: result set is smaller than all ─────────────────

    @Test
    fun `simple wifi query returns much fewer skills than total`() {
        val result = route("turn on wifi")
        assertTrue(
            "Expected significant reduction, got ${result.size} / ${ALL_SKILL_IDS.size}",
            result.size < ALL_SKILL_IDS.size / 2,
        )
    }

    @Test
    fun `simple sms query returns much fewer skills than total`() {
        val result = route("send a text message")
        assertTrue(
            "Expected significant reduction, got ${result.size} / ${ALL_SKILL_IDS.size}",
            result.size < ALL_SKILL_IDS.size / 2,
        )
    }

    @Test
    fun `dGEN1 simple query still achieves reduction`() {
        val result = route("turn on wifi", tier = Tier.PRIVILEGED)
        assertTrue(
            "Expected significant reduction on dGEN1, got ${result.size} / ${ALL_SKILL_IDS.size}",
            result.size < ALL_SKILL_IDS.size / 2,
        )
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `empty message triggers fallback`() {
        val result = route("")
        assertEquals(ALL_SKILL_IDS, result)
    }

    @Test
    fun `whitespace-only message triggers fallback`() {
        val result = route("   ")
        assertEquals(ALL_SKILL_IDS, result)
    }

    @Test
    fun `emoji-only message triggers fallback`() {
        val result = route("\uD83D\uDE00\uD83D\uDE0E")
        assertEquals(ALL_SKILL_IDS, result)
    }

    @Test
    fun `very long message with keyword still matches`() {
        val longPrefix = "a".repeat(5000)
        val result = route("$longPrefix turn on wifi")
        assertTrue("connectivity" in result)
    }

    @Test
    fun `keyword embedded in a word still matches`() {
        val result = route("check wifiBroadcast status")
        assertTrue("connectivity" in result)
    }

    @Test
    fun `default tier is OPEN`() {
        val result = route("turn on wifi")
        assertFalse("led_matrix should not be included with default tier", "led_matrix" in result)
        assertFalse("terminal_text should not be included with default tier", "terminal_text" in result)
    }

    // ── Overlapping keywords ────────────────────────────────────────────

    @Test
    fun `screen keyword matches both screen and agent_display`() {
        val result = route("show me the screen")
        assertTrue("screen" in result)
        assertTrue("agent_display" in result)
    }

    @Test
    fun `light keyword matches led_matrix`() {
        val result = route("turn on the light")
        assertTrue("led_matrix" in result)
    }

    @Test
    fun `token keyword matches both wallet and bankr_trading`() {
        val result = route("buy token PEPE")
        assertTrue("bankr_trading" in result)
        assertTrue("wallet" in result)
    }

    @Test
    fun `address keyword matches wallet via crypto keywords`() {
        val result = route("what is this address")
        assertTrue("wallet" in result)
        assertFalse("contacts should NOT match bare 'address'", "contacts" in result)
    }

    @Test
    fun `address book keyword matches contacts`() {
        val result = route("look in my address book")
        assertTrue("contacts" in result)
    }

    // ── Consistent return type ──────────────────────────────────────────

    @Test
    fun `result is always a subset of enabled skill ids`() {
        val messages = listOf(
            "turn on wifi",
            "hello",
            "open app Settings",
            "trade ETH for USDC",
            "take a screenshot and send it via email",
        )
        for (msg in messages) {
            val result = route(msg)
            assertTrue(
                "Result for '$msg' should be a subset of enabled IDs",
                ALL_SKILL_IDS.containsAll(result),
            )
        }
    }

    @Test
    fun `result never contains skills not in enabled set`() {
        val small = setOf("device_info", "connectivity")
        val result = route("turn on wifi and call mom", enabled = small)
        assertTrue(small.containsAll(result))
        assertFalse("phone should not appear because it's not enabled", "phone" in result)
    }

    // ── Real-world scenario tests ───────────────────────────────────────

    @Test
    fun `time in new york should route to web_search`() {
        val result = route("tell me the time in new york")
        assertTrue("web_search should be included", "web_search" in result)
        assertTrue("Should be a targeted result, not all", result.size < ALL_SKILL_IDS.size / 2)
    }

    @Test
    fun `send eth to ens should route to wallet and ens and token_lookup`() {
        val result = route(
            "send eth in worth of 5 dollars to this ens nceornea.eth",
            tier = Tier.PRIVILEGED,
        )
        assertTrue("wallet should be included", "wallet" in result)
        assertTrue("ens should be included", "ens" in result)
        assertTrue("token_lookup should be included", "token_lookup" in result)
        assertTrue("swap should be included", "swap" in result)
    }

    @Test
    fun `order uber should route to agent_display on dGEN1`() {
        val result = route("order me an uber", tier = Tier.PRIVILEGED)
        assertTrue("agent_display should be included", "agent_display" in result)
        assertTrue("Should be a targeted result, not all", result.size < ALL_SKILL_IDS.size / 2)
    }

    @Test
    fun `order uber routes to agent_display on OPEN tier via keyword`() {
        val result = route("order me an uber")
        assertTrue("agent_display should match via 'order' and 'uber' keywords", "agent_display" in result)
    }

    @Test
    fun `agent_display always included on dGEN1 even without keyword`() {
        val result = route("turn on wifi", tier = Tier.PRIVILEGED)
        assertTrue("agent_display should always be on for dGEN1", "agent_display" in result)
    }

    @Test
    fun `agent_display NOT always included on OPEN tier`() {
        val result = route("turn on wifi")
        assertFalse("agent_display should NOT be auto-included on OPEN tier", "agent_display" in result)
    }

    @Test
    fun `weather query routes to web_search`() {
        val result = route("what's the weather like tomorrow?")
        assertTrue("web_search should be included for weather", "web_search" in result)
    }

    @Test
    fun `play music routes to agent_display`() {
        val result = route("play some jazz music")
        assertTrue("agent_display should be included for 'play'", "agent_display" in result)
    }

    @Test
    fun `watch youtube routes to agent_display`() {
        val result = route("watch a youtube video")
        assertTrue("agent_display should be included for 'watch'/'youtube'", "agent_display" in result)
    }

    // ── Conversation-aware routing ──────────────────────────────────────
    // Note: Without a NativeSkillRegistry, previousToolNames can't be resolved
    // to skill IDs. These tests verify the parameter is accepted gracefully.

    @Test
    fun `previousToolNames accepted without registry`() {
        // Without registry, tool names can't resolve to skills, but shouldn't crash
        val result = route("hello", previousToolNames = setOf("get_connectivity_status"))
        // Falls back to all skills since no keywords and no resolved conversation context
        assertEquals(ALL_SKILL_IDS, result)
    }

    @Test
    fun `previousToolNames does not interfere with keyword matching`() {
        val result = route("turn on wifi", previousToolNames = setOf("some_tool"))
        assertTrue("connectivity" in result)
    }

    // ── Session tracking ────────────────────────────────────────────────

    @Test
    fun `session tracking starts on tool notification`() {
        val r = router()
        r.notifyToolExecuted("agent_display_create", Tier.OPEN)
        assertTrue("agent_display" in r.getActiveSessions())
    }

    @Test
    fun `session tracking ends on destroy notification`() {
        val r = router()
        r.notifyToolExecuted("agent_display_create", Tier.OPEN)
        assertTrue("agent_display" in r.getActiveSessions())
        r.notifyToolExecuted("agent_display_destroy", Tier.OPEN)
        assertFalse("agent_display" in r.getActiveSessions())
    }

    @Test
    fun `active session keeps skill included even without keyword match`() {
        val r = router()
        r.notifyToolExecuted("agent_display_create", Tier.OPEN)
        // "hello" has no keywords, but agent_display has an active session
        val result = route("hello", router = r)
        assertTrue("agent_display should be included due to active session", "agent_display" in result)
        // Should NOT fall back to all skills
        assertTrue("Should be targeted, not all", result.size < ALL_SKILL_IDS.size)
    }

    @Test
    fun `clearSessions removes all sessions`() {
        val r = router()
        r.notifyToolExecuted("agent_display_create", Tier.OPEN)
        r.clearSessions()
        assertTrue(r.getActiveSessions().isEmpty())
    }

    @Test
    fun `active session combined with keyword matching`() {
        val r = router()
        r.notifyToolExecuted("agent_display_create", Tier.OPEN)
        val result = route("turn on wifi", router = r)
        assertTrue("connectivity" in result)
        assertTrue("agent_display should also be included", "agent_display" in result)
    }

    // ── Usage frequency (in-memory only, no SharedPreferences in tests) ─

    @Test
    fun `usage counts start at zero`() {
        val r = router()
        assertTrue(r.getUsageCounts().isEmpty())
    }

    @Test
    fun `notifyToolExecuted without registry does not crash`() {
        val r = router()
        // No registry, so can't resolve tool to skill — should not crash
        r.notifyToolExecuted("toggle_connectivity", Tier.OPEN)
        // Usage counts won't change since skill can't be resolved
        assertTrue(r.getUsageCounts().isEmpty())
    }

    @Test
    fun `frequency fallback not triggered with no usage data`() {
        // With no usage data, frequency fallback returns null, so full fallback triggers
        val result = route("some random gibberish xyz")
        assertEquals(ALL_SKILL_IDS, result)
    }
}
