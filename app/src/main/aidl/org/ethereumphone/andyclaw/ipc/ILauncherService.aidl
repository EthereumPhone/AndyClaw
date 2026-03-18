// ILauncherService.aidl
// Service interface for the launcher to communicate with AndyClaw.

package org.ethereumphone.andyclaw.ipc;

import org.ethereumphone.andyclaw.ipc.ILauncherCallback;

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
}
