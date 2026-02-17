// IHeartbeatService.aidl
// AIDL interface for OS-level heartbeat binding.
// The OS binds to this service and calls heartbeatNow() periodically
// to trigger the AI agent's background check loop.

package org.ethereumphone.andyclaw.ipc;

oneway interface IHeartbeatService {
    // Triggers an immediate heartbeat run.
    // The AI agent will read HEARTBEAT.md, check portfolio, update journal,
    // and optionally notify the user via XMTP.
    // The 'oneway' modifier makes this fire-and-forget (non-blocking for the caller).
    void heartbeatNow();

    // Triggers an immediate XMTP message handling cycle.
    // Called by the OS XMTPNotificationsService when a new message arrives
    // for this app's isolated identity. The actual message content is passed
    // through so the app doesn't need to read it from the SDK.
    void heartbeatNowWithXmtpMessages(String senderAddress, String messageText);
}
