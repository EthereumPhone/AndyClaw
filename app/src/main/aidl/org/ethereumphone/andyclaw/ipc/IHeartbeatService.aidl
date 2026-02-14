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
}
