package org.ethereumphone.andyclaw.flows

import java.util.concurrent.atomic.AtomicLong

/**
 * What rung 3 is actually buying, in numbers.
 *
 * `agent-first-plan.md` Phase 2 makes one measurement the kill criterion: median
 * warm-path latency for a repeated action, with zero model calls. These counters are
 * how that is read off a device instead of estimated — `AgentLoop` logs a snapshot at
 * the end of every run under `AgentRunMetrics`.
 */
object FlowMetrics {

    private val invocations = AtomicLong()
    private val completions = AtomicLong()
    private val aborts = AtomicLong()
    private val totalDurationMs = AtomicLong()
    private val stepsRun = AtomicLong()
    private val durations = ArrayDeque<Long>()

    /** Kept for the median; a warm-path measurement is 20 runs, not 20,000. */
    private const val WINDOW = 200

    fun onInvocation() {
        invocations.incrementAndGet()
    }

    fun onResult(result: FlowRunResult) {
        totalDurationMs.addAndGet(result.durationMs)
        when (result) {
            is FlowRunResult.Completed -> {
                completions.incrementAndGet()
                stepsRun.addAndGet(result.stepsRun.toLong())
                synchronized(durations) {
                    durations.addLast(result.durationMs)
                    while (durations.size > WINDOW) durations.removeFirst()
                }
            }
            is FlowRunResult.Aborted -> aborts.incrementAndGet()
        }
    }

    fun reset() {
        invocations.set(0); completions.set(0); aborts.set(0)
        totalDurationMs.set(0); stepsRun.set(0)
        synchronized(durations) { durations.clear() }
    }

    /** Median completed-replay latency in ms, or null before anything has completed. */
    fun medianCompletedMs(): Long? = synchronized(durations) {
        if (durations.isEmpty()) return null
        val sorted = durations.sorted()
        sorted[sorted.size / 2]
    }

    fun snapshot(): String = buildString {
        append("flowInvocations=").append(invocations.get())
        append(" flowCompleted=").append(completions.get())
        append(" flowAborted=").append(aborts.get())
        append(" flowSteps=").append(stepsRun.get())
        append(" flowMedianMs=").append(medianCompletedMs() ?: -1)
    }
}
