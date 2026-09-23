package android.os;

/**
 * Autopilot progress for the rear-screen HUD (FreemeBackScreen). One-way. The JSON carries only
 * whitelisted, sanitised keys — never screen contents. Append only.
 *
 * @hide
 */
oneway interface IAgentHudListener {
    void onHudState(String hudJson);
    void onHudCleared();
}
