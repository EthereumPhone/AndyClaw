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

    // Delivers a fired reminder from the OS-level alarm scheduler.
    // On ethOS, reminders are scheduled by the system service via AlarmManager
    // for reliability (survives Doze, deep sleep, reboot, app process death).
    // When the alarm fires, the OS calls this method to trigger the notification.
    void reminderFired(int reminderId, long time, String message, String label);

    // Delivers a fired cron job from the OS-level recurring alarm scheduler.
    // Unlike reminders (one-shot), cron jobs fire repeatedly at a fixed interval.
    // The OS re-schedules the next alarm automatically after each fire.
    // The reason string describes what the agent should do on each execution.
    void cronjobFired(int cronjobId, long intervalMs, String reason, String label);
}
