package org.ethereumphone.andyclaw.safety

import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * Resolves a tool name to the [ToolEffect] the pre-flight gate acts on.
 *
 * Resolution order, most specific first:
 *
 *  1. an explicit [ToolDefinition.effect] on the tool itself;
 *  2. the builtin seed table below;
 *  3. [ToolAttenuation.READ_ONLY_TOOLS], the pre-existing read-only list;
 *  4. **[ToolEffect.IRREVERSIBLE]** — fail closed.
 *
 * Step 4 is the important one. A skill downloaded from ClawHub, a tool the model
 * wrote for itself, an extension registered over the extension protocol — none of
 * them can become harmless by declaring nothing. Adding a builtin tool without
 * adding it here makes that tool need approval, which is noisy but never unsafe.
 */
object ToolEffects {

    /** The default for anything this object cannot classify. */
    val UNCLASSIFIED: ToolEffect = ToolEffect.IRREVERSIBLE

    /** Resolve from a tool definition — an explicit declaration on it wins. */
    fun of(toolDef: ToolDefinition): ToolEffect =
        toolDef.effect ?: of(toolDef.name)

    /** Resolve from a bare tool name, for paths that never see the definition. */
    fun of(toolName: String): ToolEffect {
        BUILTIN[toolName]?.let { return it }
        if (toolName in ToolAttenuation.READ_ONLY_TOOLS) return ToolEffect.READ
        return UNCLASSIFIED
    }

    /** Resolve from whichever of the two is available; the definition wins. */
    fun of(toolName: String, toolDef: ToolDefinition?): ToolEffect =
        toolDef?.let { of(it) } ?: of(toolName)

    /**
     * True when something actually named this tool's effect — an explicit
     * declaration or the seed table. False means it fell through to
     * [UNCLASSIFIED], which is worth logging while the gate is being rolled out.
     */
    fun isClassified(toolName: String, toolDef: ToolDefinition? = null): Boolean =
        toolDef?.effect != null ||
            toolName in BUILTIN ||
            toolName in ToolAttenuation.READ_ONLY_TOOLS

    // ══════════════════════════════════════════════════════════════════
    // Outbound messaging — the reply-to-sender rule
    // ══════════════════════════════════════════════════════════════════

    /**
     * Tools whose whole purpose is to put a message in front of somebody else, and
     * the input keys that name the target.
     *
     * Under `UNTRUSTED` provenance these may only address the conversation the
     * trigger arrived on. A tool listed here with an **empty** key set has no target
     * argument at all and therefore cannot be shown to stay in-thread — it is blocked.
     */
    val OUTBOUND_MESSAGE_TARGETS: Map<String, Set<String>> = mapOf(
        "send_xmtp_message" to setOf("recipient_address"),
        "send_sms" to setOf("to"),
        "gmail_send" to setOf("to"),
        "gmail_reply" to setOf("message_id", "thread_id"),
        "reply_to_notification" to setOf("key"),
        // No target argument: replies to whoever texts next, for a whole window.
        "auto_reply_sms" to emptySet(),
    )

    /**
     * Outbound tools whose recipient is fixed to the device owner, so they cannot be
     * aimed at a stranger and stay allowed under `UNTRUSTED`. This is the leg that
     * keeps "it can raise a card for the user" working.
     */
    val OWNER_ONLY_MESSAGE_TOOLS: Set<String> = setOf(
        "send_message_to_user",   // XMTP to the device's own wallet address
        "send_telegram_message",  // Telegram to the verified bot owner
    )

    // ══════════════════════════════════════════════════════════════════
    // Seed table
    // ══════════════════════════════════════════════════════════════════

    private infix fun String.eff(effect: ToolEffect) = this to effect

    /**
     * Every builtin tool, classified.
     *
     * READ         nothing outside the process changes.
     * REVERSIBLE   local state the user can put back — a reminder, a toggle, a draft.
     * IRREVERSIBLE it leaves the device, or it cannot be taken back.
     * SENSITIVE    payment or auth. `agent-os-design.md` §6: the agent never completes these.
     */
    val BUILTIN: Map<String, ToolEffect> = mapOf(
        // ── AppsSkill ────────────────────────────────────────────────
        "list_installed_apps" eff ToolEffect.READ,
        "get_app_info" eff ToolEffect.READ,
        "launch_app" eff ToolEffect.REVERSIBLE,
        "force_stop_app" eff ToolEffect.REVERSIBLE,

        // ── AudioSkill ───────────────────────────────────────────────
        "get_audio_state" eff ToolEffect.READ,
        "set_volume" eff ToolEffect.REVERSIBLE,
        "set_ringer_mode" eff ToolEffect.REVERSIBLE,

        // ── AuroraStoreSkill ─────────────────────────────────────────
        "search_apps" eff ToolEffect.READ,
        "get_app_details" eff ToolEffect.READ,
        "install_app" eff ToolEffect.IRREVERSIBLE,

        // ── BankrTradingSkill ────────────────────────────────────────
        "get_bankr_wallet" eff ToolEffect.READ,
        "get_bankr_orders" eff ToolEffect.READ,
        "get_bankr_order_details" eff ToolEffect.READ,
        "create_bankr_order" eff ToolEffect.SENSITIVE,
        "cancel_bankr_order" eff ToolEffect.IRREVERSIBLE,

        // ── CalendarSkill ────────────────────────────────────────────
        "list_events" eff ToolEffect.READ,
        "get_event" eff ToolEffect.READ,
        "create_event" eff ToolEffect.REVERSIBLE,
        "delete_event" eff ToolEffect.IRREVERSIBLE,

        // ── CameraSkill ──────────────────────────────────────────────
        "take_photo" eff ToolEffect.REVERSIBLE,
        "silent_capture" eff ToolEffect.REVERSIBLE,
        "analyze_image" eff ToolEffect.READ,

        // ── ClawHubSkill ─────────────────────────────────────────────
        "clawhub_search" eff ToolEffect.READ,
        "clawhub_browse" eff ToolEffect.READ,
        "clawhub_skill_info" eff ToolEffect.READ,
        "clawhub_list_installed" eff ToolEffect.READ,
        // Installing a registry skill grants third-party instructions the agent's
        // authority, which is the closest thing here to an auth decision.
        "clawhub_install" eff ToolEffect.SENSITIVE,
        "clawhub_update" eff ToolEffect.SENSITIVE,
        "clawhub_uninstall" eff ToolEffect.IRREVERSIBLE,

        // ── ClipboardSkill ───────────────────────────────────────────
        "read_clipboard" eff ToolEffect.READ,
        "write_clipboard" eff ToolEffect.REVERSIBLE,

        // ── CodeExecutionSkill ───────────────────────────────────────
        "execute_code" eff ToolEffect.IRREVERSIBLE,

        // ── ConnectivitySkill ────────────────────────────────────────
        "get_connectivity_status" eff ToolEffect.READ,
        "toggle_wifi" eff ToolEffect.REVERSIBLE,
        "connect_wifi_network" eff ToolEffect.REVERSIBLE,
        "forget_wifi_network" eff ToolEffect.REVERSIBLE,
        "toggle_bluetooth" eff ToolEffect.REVERSIBLE,
        "toggle_mobile_data" eff ToolEffect.REVERSIBLE,
        "toggle_airplane_mode" eff ToolEffect.REVERSIBLE,
        "toggle_hotspot" eff ToolEffect.REVERSIBLE,

        // ── ContactsSkill ────────────────────────────────────────────
        "search_contacts" eff ToolEffect.READ,
        "get_contact_details" eff ToolEffect.READ,
        "get_eth_contacts" eff ToolEffect.READ,
        "create_contact" eff ToolEffect.REVERSIBLE,
        // Rewriting a contact's ETH address re-aims every later payment at it.
        "set_eth_address" eff ToolEffect.IRREVERSIBLE,

        // ── CronjobSkill ─────────────────────────────────────────────
        "create_cronjob" eff ToolEffect.REVERSIBLE,
        "list_cronjobs" eff ToolEffect.READ,
        "cancel_cronjob" eff ToolEffect.REVERSIBLE,

        // ── CustomToolCreatorSkill ───────────────────────────────────
        // Authors BeanShell that later runs as a first-class tool.
        "create_custom_tool" eff ToolEffect.SENSITIVE,
        "list_custom_tools" eff ToolEffect.READ,
        "delete_custom_tool" eff ToolEffect.IRREVERSIBLE,
        "test_custom_tool" eff ToolEffect.IRREVERSIBLE,

        // ── DeviceInfoSkill ──────────────────────────────────────────
        "get_device_info" eff ToolEffect.READ,

        // ── DevicePowerSkill ─────────────────────────────────────────
        "reboot_device" eff ToolEffect.IRREVERSIBLE,
        "lock_screen" eff ToolEffect.REVERSIBLE,

        // ── DriveSkill ───────────────────────────────────────────────
        "drive_list" eff ToolEffect.READ,
        "drive_download" eff ToolEffect.READ,
        "drive_upload" eff ToolEffect.IRREVERSIBLE,

        // ── ENSSkill ─────────────────────────────────────────────────
        "resolve_ens" eff ToolEffect.READ,

        // ── FileSystemSkill ──────────────────────────────────────────
        "list_directory" eff ToolEffect.READ,
        "read_file" eff ToolEffect.READ,
        "file_info" eff ToolEffect.READ,
        "write_file" eff ToolEffect.IRREVERSIBLE,

        // ── GmailSkill ───────────────────────────────────────────────
        "gmail_read" eff ToolEffect.READ,
        "gmail_get" eff ToolEffect.READ,
        "gmail_send" eff ToolEffect.IRREVERSIBLE,
        "gmail_reply" eff ToolEffect.IRREVERSIBLE,

        // ── GoogleCalendarSkill ──────────────────────────────────────
        "gcal_list_events" eff ToolEffect.READ,
        "gcal_create_event" eff ToolEffect.REVERSIBLE,

        // ── LedSkill ─────────────────────────────────────────────────
        "led_list_patterns" eff ToolEffect.READ,
        "led_display_pattern" eff ToolEffect.REVERSIBLE,
        "led_flash_pattern" eff ToolEffect.REVERSIBLE,
        "led_set_custom_pattern" eff ToolEffect.REVERSIBLE,
        "led_animate" eff ToolEffect.REVERSIBLE,
        "led_set_led" eff ToolEffect.REVERSIBLE,
        "led_set_all" eff ToolEffect.REVERSIBLE,
        "led_clear" eff ToolEffect.REVERSIBLE,

        // ── LocationSkill ────────────────────────────────────────────
        "get_current_location" eff ToolEffect.READ,
        "search_nearby" eff ToolEffect.READ,
        "open_in_maps" eff ToolEffect.REVERSIBLE,
        "start_navigation" eff ToolEffect.REVERSIBLE,

        // ── MemorySkill ──────────────────────────────────────────────
        "memory_search" eff ToolEffect.READ,
        "memory_list" eff ToolEffect.READ,
        "memory_store" eff ToolEffect.REVERSIBLE,
        "memory_delete" eff ToolEffect.IRREVERSIBLE,

        // ── MessengerSkill ───────────────────────────────────────────
        "list_conversations" eff ToolEffect.READ,
        "read_messages" eff ToolEffect.READ,
        // Recipient is resolved from the device, so it can only reach the owner.
        "send_message_to_user" eff ToolEffect.REVERSIBLE,
        "send_xmtp_message" eff ToolEffect.IRREVERSIBLE,

        // ── NotificationSkill ────────────────────────────────────────
        "list_notifications" eff ToolEffect.READ,
        "dismiss_notification" eff ToolEffect.REVERSIBLE,
        "auto_triage" eff ToolEffect.REVERSIBLE,
        "set_dnd_mode" eff ToolEffect.REVERSIBLE,
        "reply_to_notification" eff ToolEffect.IRREVERSIBLE,

        // ── PackageManagerSkill ──────────────────────────────────────
        "uninstall_app" eff ToolEffect.IRREVERSIBLE,
        "clear_app_cache" eff ToolEffect.REVERSIBLE,
        "clear_app_data" eff ToolEffect.IRREVERSIBLE,

        // ── PhoneSkill ───────────────────────────────────────────────
        "get_call_log" eff ToolEffect.READ,
        "make_call" eff ToolEffect.IRREVERSIBLE,
        "answer_call" eff ToolEffect.IRREVERSIBLE,

        // ── ProactiveAgentSkill ──────────────────────────────────────
        "register_trigger" eff ToolEffect.REVERSIBLE,
        "list_triggers" eff ToolEffect.READ,
        "remove_trigger" eff ToolEffect.REVERSIBLE,

        // ── ReminderSkill ────────────────────────────────────────────
        "create_reminder" eff ToolEffect.REVERSIBLE,
        "list_reminders" eff ToolEffect.READ,
        "cancel_reminder" eff ToolEffect.REVERSIBLE,

        // ── SMSSkill ─────────────────────────────────────────────────
        "read_sms" eff ToolEffect.READ,
        "send_sms" eff ToolEffect.IRREVERSIBLE,
        "auto_reply_sms" eff ToolEffect.IRREVERSIBLE,

        // ── ScreenSkill / ScreenTimeSkill ────────────────────────────
        "read_screen" eff ToolEffect.READ,
        "get_usage_stats" eff ToolEffect.READ,
        "get_app_usage" eff ToolEffect.READ,

        // ── SettingsSkill ────────────────────────────────────────────
        "get_system_setting" eff ToolEffect.READ,
        "list_settings" eff ToolEffect.READ,
        "write_system_setting" eff ToolEffect.REVERSIBLE,
        // Settings.Secure carries accessibility and auth posture.
        "write_secure_setting" eff ToolEffect.SENSITIVE,

        // ── SheetsSkill ──────────────────────────────────────────────
        "sheets_read" eff ToolEffect.READ,
        "sheets_append" eff ToolEffect.IRREVERSIBLE,

        // ── ShellSkill / TermuxSkill ─────────────────────────────────
        "run_shell_command" eff ToolEffect.IRREVERSIBLE,
        "termux_run_command" eff ToolEffect.IRREVERSIBLE,
        "termux_check_status" eff ToolEffect.READ,

        // ── SkillCreatorSkill ────────────────────────────────────────
        "skill_list_references" eff ToolEffect.READ,
        "skill_read_source" eff ToolEffect.READ,
        "skill_list_created" eff ToolEffect.READ,
        "skill_create" eff ToolEffect.IRREVERSIBLE,
        "skill_write_file" eff ToolEffect.IRREVERSIBLE,
        "skill_delete" eff ToolEffect.IRREVERSIBLE,

        // ── SkillRefinementSkill ─────────────────────────────────────
        "refinement_list_skills" eff ToolEffect.READ,
        "refinement_read" eff ToolEffect.READ,
        "refinement_list_all" eff ToolEffect.READ,
        "refinement_create" eff ToolEffect.REVERSIBLE,
        "refinement_remove" eff ToolEffect.IRREVERSIBLE,

        // ── SoulSkill ────────────────────────────────────────────────
        "read_soul" eff ToolEffect.READ,
        // The soul is standing instructions; rewriting it is persistent injection.
        "update_soul" eff ToolEffect.IRREVERSIBLE,

        // ── StorageSkill ─────────────────────────────────────────────
        "list_storage_directory" eff ToolEffect.READ,
        "read_storage_file" eff ToolEffect.READ,
        "search_files" eff ToolEffect.READ,
        "get_storage_info" eff ToolEffect.READ,

        // ── SwapSkill ────────────────────────────────────────────────
        "swap_tokens" eff ToolEffect.SENSITIVE,

        // ── TelegramSkill ────────────────────────────────────────────
        "list_telegram_chats" eff ToolEffect.READ,
        "send_telegram_message" eff ToolEffect.REVERSIBLE,

        // ── TokenLookupSkill ─────────────────────────────────────────
        "lookup_token" eff ToolEffect.READ,
        "get_token_price" eff ToolEffect.READ,
        "get_launched_tokens" eff ToolEffect.READ,

        // ── WalletSkill ──────────────────────────────────────────────
        // Reads.
        "get_user_wallet_address" eff ToolEffect.READ,
        "get_agent_wallet_address" eff ToolEffect.READ,
        "get_owned_tokens" eff ToolEffect.READ,
        "get_swap_quote" eff ToolEffect.READ,
        "resolve_token" eff ToolEffect.READ,
        "read_wallet_holdings" eff ToolEffect.READ,
        "read_agent_balance" eff ToolEffect.READ,
        // The user's wallet — every one of these ends at the SystemUI confirmation.
        "propose_transaction" eff ToolEffect.SENSITIVE,
        "propose_token_transfer" eff ToolEffect.SENSITIVE,
        "send_native_token" eff ToolEffect.SENSITIVE,
        "send_token" eff ToolEffect.SENSITIVE,
        // The agent's own sub-account — deliberately promptless, which is exactly
        // why provenance has to gate it.
        "agent_send_transaction" eff ToolEffect.IRREVERSIBLE,
        "agent_transfer_token" eff ToolEffect.IRREVERSIBLE,
        "agent_send_native_token" eff ToolEffect.IRREVERSIBLE,
        "agent_send_token" eff ToolEffect.IRREVERSIBLE,
        "agent_swap" eff ToolEffect.IRREVERSIBLE,

        // ── WebSearchSkill ───────────────────────────────────────────
        "web_search" eff ToolEffect.READ,
        "fetch_webpage" eff ToolEffect.READ,

        // ── CliToolManagerSkill ──────────────────────────────────────
        "cli_tools_list" eff ToolEffect.READ,
        "cli_tools_info" eff ToolEffect.READ,
        "cli_tools_add" eff ToolEffect.REVERSIBLE,
        "cli_tools_remove" eff ToolEffect.REVERSIBLE,
        "cli_tools_configure" eff ToolEffect.REVERSIBLE,
        "cli_tools_install" eff ToolEffect.IRREVERSIBLE,
        "cli_tools_run" eff ToolEffect.IRREVERSIBLE,

        // ── AgentDisplaySkill ────────────────────────────────────────
        // Reading the shadow screen is free.
        "agent_display_look" eff ToolEffect.READ,
        "agent_display_screenshot" eff ToolEffect.READ,
        "agent_display_get_info" eff ToolEffect.READ,
        "agent_display_get_ui_tree" eff ToolEffect.READ,
        "agent_display_get_node_info" eff ToolEffect.READ,
        "agent_display_current_activity" eff ToolEffect.READ,
        "agent_display_get_clipboard" eff ToolEffect.READ,
        // Managing the surface itself changes nothing inside an app.
        "agent_display_create" eff ToolEffect.REVERSIBLE,
        "agent_display_destroy" eff ToolEffect.REVERSIBLE,
        "agent_display_destroy_and_promote" eff ToolEffect.REVERSIBLE,
        "agent_display_resize" eff ToolEffect.REVERSIBLE,
        "agent_display_set_clipboard" eff ToolEffect.REVERSIBLE,
        // Injection acts *inside* somebody else's app, which is how a send button
        // gets pressed without ever naming a messaging tool. Until Phase 2's flows
        // can declare a checkpoint before the irreversible step, the whole
        // injection surface is irreversible.
        "agent_display_launch_activity" eff ToolEffect.IRREVERSIBLE,
        "agent_display_launch_intent" eff ToolEffect.IRREVERSIBLE,
        "agent_display_tap" eff ToolEffect.IRREVERSIBLE,
        "agent_display_double_tap" eff ToolEffect.IRREVERSIBLE,
        "agent_display_long_press" eff ToolEffect.IRREVERSIBLE,
        "agent_display_swipe" eff ToolEffect.IRREVERSIBLE,
        "agent_display_drag" eff ToolEffect.IRREVERSIBLE,
        "agent_display_fling" eff ToolEffect.IRREVERSIBLE,
        "agent_display_pinch" eff ToolEffect.IRREVERSIBLE,
        "agent_display_gesture" eff ToolEffect.IRREVERSIBLE,
        "agent_display_press_back" eff ToolEffect.IRREVERSIBLE,
        "agent_display_press_enter" eff ToolEffect.IRREVERSIBLE,
        "agent_display_press_home" eff ToolEffect.IRREVERSIBLE,
        "agent_display_press_key" eff ToolEffect.IRREVERSIBLE,
        "agent_display_press_recents" eff ToolEffect.IRREVERSIBLE,
        "agent_display_type_text" eff ToolEffect.IRREVERSIBLE,
        "agent_display_type_text_slow" eff ToolEffect.IRREVERSIBLE,
        "agent_display_click_node" eff ToolEffect.IRREVERSIBLE,
        "agent_display_long_click_node" eff ToolEffect.IRREVERSIBLE,
        "agent_display_focus_node" eff ToolEffect.IRREVERSIBLE,
        "agent_display_scroll_node" eff ToolEffect.IRREVERSIBLE,
        "agent_display_set_node_text" eff ToolEffect.IRREVERSIBLE,
    )
}
