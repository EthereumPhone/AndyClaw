package android.os;

/**
 * Callbacks from AgentDisplayService to the AndyClaw app. One-way, so system_server never waits
 * on the app. Append only: the app compiles its own copy of this file.
 *
 * @hide
 */
oneway interface IAgentDisplayListener {
    /** The user (or the system) stopped the agent. [source] is e.g. "rear_hud" or "app". */
    void onStopRequested(String source);
    /** 0 = released, 1 = live, 2 = parked. */
    void onDisplayStateChanged(int displayId, int state);
}
