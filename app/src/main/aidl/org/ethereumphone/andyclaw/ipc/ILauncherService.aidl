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
}
