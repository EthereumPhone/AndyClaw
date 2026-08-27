// ILauncherService.aidl
// Service interface for the launcher to communicate with AndyClaw.

package org.ethereumphone.andyclaw.ipc;

import org.ethereumphone.andyclaw.ipc.ILauncherCallback;
import org.ethereumphone.andyclaw.ipc.IExecSummaryCallback;

interface ILauncherService {
    // Returns true if AndyClaw has been set up (user_story.md exists with content).
    boolean isSetup();

    // Returns the user-chosen AI name, or "AndyClaw" if not configured.
    String getAiName();

    // Sends a text prompt to the agent and streams the response via callback.
    // sessionId groups messages into a conversation for multi-turn context.
    void sendPrompt(String prompt, String sessionId, ILauncherCallback callback);

    // Transcribes an audio file using Whisper large-v3-turbo.
    // audioFd is a file descriptor for the recorded audio file (cross-process safe).
    void transcribeAudio(in ParcelFileDescriptor audioFd, ILauncherCallback callback);

    // Clears conversation history for a given session.
    void clearSession(String sessionId);

    // Sends a prompt from the lockscreen voice flow. After execution, the
    // executive summary is updated to reflect the command that was run.
    void sendLockscreenPrompt(String prompt, String sessionId, ILauncherCallback callback);

    // Returns recent launcher sessions as a JSON array.
    // Each object has: id, title, updatedAt (epoch ms).
    String getRecentSessions(int limit);

    // Returns messages for a session as a JSON array.
    // Each object has: role ("user"/"assistant"/"system"/"tool"), content, timestamp.
    String getSessionMessages(String sessionId);

    // Returns all settings as a JSON object string.
    String getSettings();

    // Sets a single setting by key. Value is a string ("true"/"false" for bools, numbers as strings).
    // Returns true on success.
    boolean setSetting(String key, String value);

    // Returns available AI providers as a JSON array.
    // Each: { "name": "OPEN_ROUTER", "displayName": "OpenRouter", "isConfigured": true }
    String getAvailableProviders();

    // Returns available models for a provider as a JSON array.
    // Each: { "modelId": "...", "name": "Claude Sonnet 4.6" }
    String getAvailableModels(String providerName);

    // Deletes a session permanently.
    void deleteSession(String sessionId);

    // Resumes a previous session — loads its message history into the in-memory
    // conversation buffer so subsequent sendPrompt() calls continue the conversation.
    void resumeSession(String sessionId);

    // Cancels any in-flight inference for the given session.
    // The coroutine running the agent loop is cancelled, which stops LLM streaming
    // and tool execution. The callback receives onError("Cancelled") before cleanup.
    void stopInference(String sessionId);

    // ── Telegram ──────────────────────────────────────────────────────────
    // Completes Telegram setup (stores token + chat ID, enables bot, notifies OS).
    boolean completeTelegramSetup(String token, long ownerChatId);
    // Clears Telegram setup (disables bot, clears token + chat ID, notifies OS).
    void clearTelegramSetup();

    // ── Memory ────────────────────────────────────────────────────────────
    // Returns the count of stored memories.
    int getMemoryCount();
    // Triggers a full memory reindex. Non-blocking.
    void reindexMemory();
    // Deletes all stored memories. Non-blocking.
    void clearAllMemories();
    // Returns true if a memory reindex is currently in progress.
    boolean isReindexing();

    // ── Extensions ────────────────────────────────────────────────────────
    // Returns installed extensions as a JSON array.
    String getExtensions();
    // Triggers extension rescan. Non-blocking.
    void rescanExtensions();

    // ── Skills ────────────────────────────────────────────────────────────
    // Returns all registered skills as a JSON array.
    String getRegisteredSkills();
    // Returns the set of enabled skill IDs as a JSON array of strings.
    String getEnabledSkills();
    // Toggles a skill on or off.
    void toggleSkill(String skillId, boolean enabled);

    // ── Routing Presets ───────────────────────────────────────────────────
    // Returns all routing presets as a JSON array.
    String getRoutingPresets();
    // Selects a routing preset by ID.
    void selectRoutingPreset(String presetId);

    // ── Paymaster ─────────────────────────────────────────────────────────
    // Returns the paymaster balance as a string (e.g. "12.50"), or null if unavailable.
    String getPaymasterBalance();

    // ── Agent Wallet ──────────────────────────────────────────────────────
    // Returns the agent wallet address, or null if SubWalletSDK is unavailable.
    String getAgentWalletAddress();

    // ── Google OAuth ──────────────────────────────────────────────────────
    // Starts the Google OAuth flow (opens browser, runs loopback server). Non-blocking.
    void startGoogleOAuthFlow();
    // Disconnects Google (clears all Google OAuth tokens).
    void disconnectGoogle();

    // ── Local Model ───────────────────────────────────────────────────────
    // Triggers download of the local model. Non-blocking.
    void downloadLocalModel();
    // Deletes the downloaded local model.
    void deleteLocalModel();

    // ── Routing Presets (extended) ────────────────────────────────────────
    // Returns all routing presets with full detail (coreSkillIds, tools, etc.) as JSON.
    String getRoutingPresetsDetailed();
    // Saves a routing preset from JSON. Upserts by ID.
    void saveRoutingPreset(String presetJson);
    // Deletes a custom routing preset by ID.
    void deleteRoutingPreset(String presetId);
    // Reverts a stock routing preset to its default configuration.
    void revertStockPreset(String presetId);

    // ── Agent Transactions ────────────────────────────────────────────
    // Returns agent wallet transactions as a JSON array, ordered by timestamp desc.
    String getAgentTransactions();
    // Clears all agent wallet transactions.
    void clearAgentTransactions();

    // ── Executive Summary ─────────────────────────────────────────────
    // Returns the current cached executive summary text, or "" if none.
    String getExecutiveSummary();
    // Registers a callback for real-time exec summary streaming updates.
    // Only one callback is active at a time; registering a new one replaces the previous.
    void registerExecSummaryCallback(IExecSummaryCallback callback);
    // Unregisters the exec summary streaming callback.
    void unregisterExecSummaryCallback();
    // Dismisses a bullet from the exec summary so the LLM won't regenerate similar content.
    void dismissExecSummaryBullet(String bulletText);

    // ── Local LLM GGUF management ──────────────────────────────────────
    // Returns the GGUFs in filesDir/models/ as a JSON array:
    //   [{ "filename", "displayName", "sizeBytes", "isBuiltin" }]
    String getGgufModels();
    // Copies a user-picked .gguf (passed as an fd) into filesDir/models/.
    // Synchronous — caller should run off the main thread + show progress.
    // Returns true on success.
    boolean importGguf(in ParcelFileDescriptor fd, String displayName);
    // Deletes an imported GGUF by filename (refuses the builtin). True on success.
    boolean deleteGguf(String filename);

    // ── Heartbeat logs ────────────────────────────────────────────────
    // IMPORTANT: these sit at the same ordinals as the launcher's copy of
    // this AIDL — right after deleteGguf and BEFORE its clawHub* block.
    // Anything new must be appended after the launcher's clawHub methods,
    // never inserted here, or every later transaction code shifts.
    //
    // Returns heartbeat run logs as a JSON array, newest first. Each object:
    //   { "timestampMs", "outcome", "prompt", "responseText", "error",
    //     "durationMs", "toolCalls": [{ "toolName", "result" }] }
    String getHeartbeatLogs();
    // Deletes all stored heartbeat run logs.
    void clearHeartbeatLogs();

    // ── ClawHub (ordinals 50-60) ──────────────────────────────────────
    // DECLARED HERE ONLY TO HOLD THE ORDINALS. The launcher's copy of this AIDL has
    // carried this block since before the app's copy existed, so on the wire slots 50-60
    // already mean these methods: without them, the first method appended below would sit
    // at 50 and the launcher's clawHubSearch() would call it. The skill marketplace itself
    // is not implemented in the app -- these return the same empty answers the launcher
    // already handles, which is exactly what it sees today when the transaction finds
    // nothing at all. Implementing ClawHub for real means filling these in, never moving
    // them.
    String clawHubSearch(String query, int limit);
    String clawHubBrowse(String cursor);
    String clawHubListInstalled();
    boolean clawHubIsInstalled(String slug);
    String clawHubDownloadAndAssess(String slug);
    String clawHubConfirmInstall(String slug, String version);
    void clawHubCancelPendingInstall(String slug);
    boolean clawHubUninstall(String slug);
    String clawHubUpdate(String slug);
    String clawHubReadSkillContent(String slug);
    String clawHubGetRiskData(String slug);

    // ══════════════════════════════════════════════════════════════════
    // APPEND POINT. Everything below is new in this release and must land in the
    // launcher's copy at the same ordinals, in the same order, in the same release.
    //
    // Both directions of the version skew are handled by returning nothing rather than
    // failing: a launcher older than this app never calls any of them, and a launcher
    // newer than the installed app gets a transaction that finds no method, whose reply
    // parcel is empty -- so every call below must be null-safe on the launcher side.
    // ══════════════════════════════════════════════════════════════════

    // ── Ambient card stack ────────────────────────────────────────────
    // The things the device expects to matter soon, already ranked by time-to-relevance,
    // newest-relevant first. Nothing here was written by a model: every field was parsed
    // deterministically out of something that was already structured. JSON array:
    //   [{ "id", "kind", "title", "subtitle", "startMs", "endMs", "location",
    //      "payload": {...}, "score", "untilStartMs", "source" }]
    String getPredictedCards(int limit);
    // Waves a card away. It comes back only if the underlying thing actually changes --
    // a moved departure time is exactly when it should.
    void dismissPredictedCard(String id);

    // ── Pending approvals ─────────────────────────────────────────────
    // Actions a run refused to take on its own because its trigger was untrusted, waiting
    // for the user. This is what an ActionConfirmCard renders. JSON array:
    //   [{ "id", "timestampMs", "source", "provenance", "toolName", "description",
    //      "conversationId", "inputPreview" }]
    String getPendingApprovals();
    // Resolves one, either way, and writes the decision to the ledger. Approving does not
    // replay the refused call -- the store deliberately keeps only a truncated preview of
    // its arguments -- so the launcher follows an approval with a normal sendPrompt() from
    // the user, which is USER-provenance and passes the gate honestly. True if it existed.
    boolean resolvePendingApproval(String id, boolean approved);

    // ── Ledger ────────────────────────────────────────────────────────
    // The append-only, hash-chained record of what the agent did, newest first. A TURN row
    // is a run; the TOOL rows under it are its steps. Rows carry no tool input and no tool
    // output, by design. JSON array of
    //   { "id", "seq", "sessionId", "ts", "kind", "intent", "provenance", "routeRung",
    //     "flowRef", "actions": [{ "tool", "ok", "durationMs", "note" }], "frames": [],
    //     "outcome", "modelIds": [], "costUsd" (null when unknown -- not zero),
    //     "inputTokens", "outputTokens", "durationMs", "prevHash", "hash" }
    String getLedgerEntries(int limit);
    // One session assembled for playback: its rows, the frames still on disk, and the
    // frames the rows name that retention has since evicted. JSON:
    //   { "sessionId", "intent", "startedMs", "endedMs", "entries": [...],
    //     "frames": [{ "id", "index", "timestampMs", "sizeBytes" }], "missingFrames": [] }
    String getLedgerSession(String sessionId);
    // Re-hashes the whole chain. JSON: { "ok", "checked", "brokenAtSeq" (null when ok) }.
    String verifyLedger();
    // One captured frame, by the id the ledger names, as a read-only fd. Null if evicted.
    ParcelFileDescriptor openLedgerFrame(String frameId);
    // The whole ledger as JSONL, one row per line, for "exportable and verifiable".
    ParcelFileDescriptor exportLedger();
}
