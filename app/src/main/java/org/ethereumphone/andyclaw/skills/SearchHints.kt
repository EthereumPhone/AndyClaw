package org.ethereumphone.andyclaw.skills

/**
 * Centralized search hints for built-in tools.
 *
 * Each hint is a short phrase (3-10 words) of discovery keywords that help
 * [ToolSearchService] find the tool. Prefer terms NOT already in the tool name
 * (e.g. "inbox threads" for `read_sms`, not "read" or "sms").
 *
 * Indexed at 2x weight in BM25 (between name at 3x and description at 1x).
 * Also shown alongside sibling tool names so the model can decide whether to load them.
 */
object SearchHints {

    private val hints = mapOf(
        // ── Phone ──
        "get_call_log" to "call history recent missed incoming outgoing",
        "make_call" to "dial phone number ring cellular",
        "answer_call" to "pick up incoming ringing accept",

        // ── SMS ──
        "read_sms" to "inbox text messages threads received",
        "send_sms" to "text message compose MMS",
        "auto_reply_sms" to "automatic response away message",

        // ── Clipboard ──
        "read_clipboard" to "paste copied text buffer",
        "write_clipboard" to "copy text to clipboard buffer",

        // ── Telegram ──
        "send_telegram_message" to "chat bot channel group",
        "list_telegram_chats" to "conversations contacts groups channels",

        // ── Device Power ──
        "reboot_device" to "restart shutdown power cycle",
        "lock_screen" to "sleep display off secure",

        // ── Memory (CORE — hints still help catalog summary) ──
        "memory_search" to "recall remember past context lookup",
        "memory_list" to "browse stored memories inventory",
        "memory_store" to "remember save persist note",
        "memory_delete" to "forget remove erase",

        // ── Google Drive ──
        "drive_list" to "google drive files folders browse",
        "drive_download" to "google drive fetch save locally",
        "drive_upload" to "google drive push share cloud",

        // ── Calendar ──
        "list_events" to "schedule agenda appointments upcoming",
        "get_event" to "event details calendar appointment",
        "create_event" to "schedule meeting appointment book",
        "delete_event" to "cancel remove event unschedule",

        // ── Google Calendar ──
        "gcal_list_events" to "google calendar schedule agenda",
        "gcal_create_event" to "google calendar meeting appointment",

        // ── Wallet (core crypto) ──
        "get_user_wallet_address" to "ethereum address public key account",
        "get_owned_tokens" to "portfolio holdings balance ERC20",
        "get_swap_quote" to "exchange rate price DEX trade",
        "propose_transaction" to "send ETH sign approve contract",
        "propose_token_transfer" to "send ERC20 token transfer",
        "get_agent_wallet_address" to "sub-account autonomous wallet",
        "agent_send_transaction" to "autonomous ETH send contract",
        "agent_transfer_token" to "autonomous ERC20 transfer",
        "resolve_token" to "token address lookup symbol ticker",
        "send_native_token" to "send ETH native currency transfer",
        "send_token" to "send ERC20 token transfer",
        "agent_send_native_token" to "autonomous ETH send",
        "agent_send_token" to "autonomous ERC20 send",
        "agent_swap" to "autonomous DEX exchange trade",
        "read_wallet_holdings" to "portfolio balance tokens owned",
        "read_agent_balance" to "sub-account holdings autonomous",

        // ── ENS ──
        "resolve_ens" to "ethereum name service domain lookup .eth",

        // ── Token Lookup ──
        "lookup_token" to "crypto token info contract address metadata",
        "get_token_price" to "crypto price market cap chart value",
        "get_launched_tokens" to "new tokens recently deployed launches",

        // ── Swap ──
        "swap_tokens" to "exchange trade DEX uniswap sushiswap ERC20",

        // ── Bankr Trading ──
        "get_bankr_wallet" to "trading account portfolio bankr",
        "get_bankr_orders" to "open orders trades positions bankr",
        "get_bankr_order_details" to "order status fill price bankr",
        "create_bankr_order" to "place trade buy sell limit market bankr",
        "cancel_bankr_order" to "revoke cancel trade order bankr",

        // ── Contacts ──
        "search_contacts" to "find person phone number address book",
        "get_contact_details" to "person info phone email address",
        "get_eth_contacts" to "ethereum wallet contacts addresses",
        "create_contact" to "add new person phone number save",
        "set_eth_address" to "link ethereum wallet to contact",

        // ── Camera ──
        "take_photo" to "capture picture camera snapshot image",
        "silent_capture" to "background photo capture stealth quiet",
        "analyze_image" to "vision describe photo OCR recognize",

        // ── Apps ──
        "list_installed_apps" to "all applications packages installed",
        "launch_app" to "open start run application",
        "get_app_info" to "application details version size package",
        "force_stop_app" to "kill terminate close application process",

        // ── Notifications ──
        "list_notifications" to "alerts badges status bar messages",
        "dismiss_notification" to "clear swipe remove alert",
        "reply_to_notification" to "respond inline quick reply",
        "auto_triage" to "sort prioritize categorize alerts",
        "set_dnd_mode" to "do not disturb silent focus quiet",

        // ── File System ──
        "list_directory" to "browse files folders contents ls",
        "read_file" to "open view content text cat",
        "write_file" to "create save edit content output",
        "file_info" to "size date permissions metadata stat",

        // ── Storage ──
        "list_storage_directory" to "external SD card shared browse",
        "read_storage_file" to "external shared file content read",
        "search_files" to "find locate glob pattern recursive",
        "get_storage_info" to "disk space free used capacity",

        // ── Shell ──
        "run_shell_command" to "bash terminal execute command line CLI",

        // ── Termux ──
        "termux_run_command" to "linux bash terminal package apt pip",
        "termux_check_status" to "termux installed running available",

        // ── Code Execution ──
        "execute_code" to "run java beanshell script evaluate compute",

        // ── Connectivity ──
        "get_connectivity_status" to "wifi bluetooth mobile data network",
        "toggle_wifi" to "wireless network enable disable on off",
        "connect_wifi_network" to "join SSID password wireless",
        "forget_wifi_network" to "remove saved wireless SSID",
        "toggle_bluetooth" to "BT pair enable disable on off",
        "toggle_mobile_data" to "cellular LTE 5G data enable disable",
        "toggle_airplane_mode" to "flight mode radio off all wireless",
        "toggle_hotspot" to "tethering mobile hotspot share internet",

        // ── Location ──
        "get_current_location" to "GPS coordinates latitude longitude position",
        "search_nearby" to "places restaurants stores points of interest",
        "open_in_maps" to "google maps directions navigate show",
        "start_navigation" to "driving directions route turn by turn",

        // ── Audio ──
        "get_audio_state" to "volume level ringer mode sound",
        "set_volume" to "loudness media ring alarm level",
        "set_ringer_mode" to "silent vibrate normal sound mode",

        // ── Screen ──
        "read_screen" to "screenshot capture current display UI",

        // ── Device Info ──
        "get_device_info" to "battery model OS version hardware specs",

        // ── Settings ──
        "get_system_setting" to "android system preference value read",
        "list_settings" to "all android settings preferences browse",
        "write_system_setting" to "change android system preference update",
        "write_secure_setting" to "change android secure preference update",

        // ── Screen Time ──
        "get_usage_stats" to "screen time daily weekly statistics",
        "get_app_usage" to "time spent application foreground hours",

        // ── Gmail ──
        "gmail_send" to "email compose send google mail",
        "gmail_read" to "email inbox unread google mail list",
        "gmail_get" to "email read body content thread",
        "gmail_reply" to "email respond reply thread google",

        // ── Google Sheets ──
        "sheets_read" to "spreadsheet google cells range data",
        "sheets_append" to "spreadsheet google add row write",

        // ── Web Search ──
        "web_search" to "google search internet browse find online",
        "fetch_webpage" to "download URL page content HTML scrape",

        // ── Soul ──
        "update_soul" to "personality character values traits behavior",
        "read_soul" to "personality identity who am I character",

        // ── Aurora Store ──
        "search_apps" to "play store find download APK browse",
        "get_app_details" to "play store app info reviews rating",
        "install_app" to "play store download APK sideload",

        // ── Reminder ──
        "create_reminder" to "alarm timer schedule alert notify later",
        "list_reminders" to "pending scheduled alarms upcoming",
        "cancel_reminder" to "delete remove alarm timer",

        // ── Cronjob ──
        "create_cronjob" to "schedule recurring periodic timer automated",
        "list_cronjobs" to "scheduled jobs recurring tasks timers",
        "cancel_cronjob" to "stop remove recurring job unschedule",

        // ── Messenger (XMTP) ──
        "send_message_to_user" to "XMTP web3 encrypted decentralized chat",
        "send_xmtp_message" to "XMTP web3 encrypted direct message",
        "list_conversations" to "XMTP chats threads contacts",
        "read_messages" to "XMTP chat history thread messages",

        // ── Package Manager ──
        "uninstall_app" to "remove delete application APK",
        "clear_app_cache" to "free space temporary files cleanup",
        "clear_app_data" to "reset application factory wipe storage",

        // ── ClawHub ──
        "clawhub_search" to "community skills marketplace extensions",
        "clawhub_browse" to "community skills catalog categories",
        "clawhub_skill_info" to "skill details readme documentation",
        "clawhub_install" to "add skill extension plugin download",
        "clawhub_uninstall" to "remove skill extension plugin delete",
        "clawhub_update" to "upgrade skill extension newer version",
        "clawhub_list_installed" to "my skills extensions installed active",

        // ── LED (dGEN1) ──
        "led_display_pattern" to "LED matrix light show visual",
        "led_flash_pattern" to "LED blink strobe pulse visual",
        "led_set_custom_pattern" to "LED matrix pixel art custom",
        "led_animate" to "LED animation effect visual motion",
        "led_set_led" to "LED single pixel set color",
        "led_set_all" to "LED matrix fill solid color",
        "led_clear" to "LED matrix off blank reset",
        "led_list_patterns" to "LED available animations effects",

        // ── Terminal Text (dGEN1) ──
        // (CORE on PRIVILEGED tier — hints for catalog summary)

        // ── Agent Display ──
        "agent_display_create" to "virtual screen headless display",
        "agent_display_screenshot" to "capture virtual screen image",
        "agent_display_tap" to "click touch virtual screen UI",
        "agent_display_type_text" to "keyboard input virtual screen type",
        "agent_display_get_ui_tree" to "accessibility nodes elements DOM",
        "agent_display_click_node" to "accessibility click element button",
        "agent_display_launch_activity" to "open app virtual screen start",
        "agent_display_swipe" to "scroll gesture virtual screen drag",

        // ── Custom Tools ──
        "create_custom_tool" to "define new tool beanshell script",
        "list_custom_tools" to "user-defined tools scripts inventory",
        "delete_custom_tool" to "remove user-defined tool script",
        "test_custom_tool" to "run try execute custom script",

        // ── Skill Creator ──
        "skill_create" to "build new skill extension plugin",
        "skill_list_created" to "my created skills extensions",
        "skill_delete" to "remove created skill extension",

        // ── Proactive Agent ──
        "register_trigger" to "automation event-driven when condition",
        "list_triggers" to "automations active registered events",
        "remove_trigger" to "stop automation unregister event",

        // ── CLI Tool Manager ──
        "cli_tools_list" to "termux packages binaries available",
        "cli_tools_add" to "termux install package binary",
        "cli_tools_remove" to "termux uninstall package binary",
        "cli_tools_run" to "termux execute binary command",
    )

    /**
     * Returns the search hint for a tool, or null if none defined.
     * Called by [ToolSearchService.buildCatalog] when constructing catalog entries.
     */
    fun forTool(toolName: String): String? = hints[toolName]
}
