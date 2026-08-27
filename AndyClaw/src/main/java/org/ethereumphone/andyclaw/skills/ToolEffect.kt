package org.ethereumphone.andyclaw.skills

/**
 * What executing a tool does to the world.
 *
 * Confirmation policy is a pure function of this class plus the run's
 * [org.ethereumphone.andyclaw.ExecutionEngine.Provenance] — evaluated by the
 * execution engine, not by the model, so the model cannot talk its way past it.
 *
 * This is deliberately **not** [Tier]. Tier answers "does this device expose the
 * capability at all"; effect answers "what happens if it runs".
 *
 * Anything that does not resolve to an effect is treated as [IRREVERSIBLE] —
 * see `ToolEffects` in the app module. Fail closed.
 */
enum class ToolEffect {
    /** Query only. Nothing outside the process changes. */
    READ,

    /** Changes local state the user can trivially undo — a draft, a reminder, a note. */
    REVERSIBLE,

    /** Leaves the device or cannot be taken back — send, post, delete, install, spend. */
    IRREVERSIBLE,

    /** Payment, authentication, or anything on a FLAG_SECURE surface. Never automated. */
    SENSITIVE,
}
