package org.ethereumphone.andyclaw.ingest

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ethereumphone.andyclaw.ambient.PredictedContext
import org.ethereumphone.andyclaw.ambient.PredictedContextRepository
import org.ethereumphone.andyclaw.ambient.PredictedContextScorer
import java.time.ZoneId

/** What one ingest did. Returned rather than logged so a caller can decide what it means. */
data class IngestReport(
    val signal: AmbientSignal,
    val ran: Boolean,
    val mailsScanned: Int = 0,
    val reservations: Int = 0,
    val calendarEvents: Int = 0,
    val contextsWritten: Int = 0,
    val skippedReason: String? = null,
    /** What the launcher is told: [IngestState.state]. */
    val state: String = IngestState.OK,
)

/**
 * Mail and calendar in, [PredictedContext] out.
 *
 * The whole point of Phase 3.3 is that nothing here is a judgement call: the sources fetch,
 * the parsers decode, the mapper builds cards, and the repository deduplicates on the real
 * world rather than on the message. A model is not involved at any step and could not be
 * inserted at one without changing this file's dependencies, which is what
 * `IngestNoModelCallTest` checks.
 *
 * The order is the order of authority. The calendars themselves are read first — Google's
 * over the API, the phone's own through the provider — and anything they no longer return is
 * removed, because a deleted or declined event must not keep its card. Then mail: only
 * messages not already read in full, and an invitation that came by mail only where no
 * calendar has spoken for the same event.
 *
 * One ingest at a time. Two concurrent sweeps would fetch the same mail twice and race each
 * other into [PredictedContextRepository.put] for the same `sourceKey` — where one of them
 * would lose its insert to the other's, and the pair would burn two round trips to produce
 * one row.
 */
class AmbientIngestor(
    private val mail: MailSource,
    /** Google Calendar over its API; rows it writes are `gcal`. */
    private val calendar: CalendarSource,
    private val contexts: PredictedContextRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Read per ingest: a zone captured once went stale the first time the user travelled. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Gate for the whole feature, read per call so a settings change takes effect at once. */
    private val enabled: () -> Boolean = { true },
    /** The phone's own calendars; rows it writes are `device-calendar`. */
    private val deviceCalendar: CalendarSource? = null,
    private val stateStore: IngestStateStore = IngestStateStore.InMemory(),
    private val seen: IngestSeenStore = IngestSeenStore.InMemory(),
) {

    private val lock = Mutex()

    /** When the last successful ingest ran, for the ambient surface and for tests. */
    val lastIngestAtMs: Long get() = stateStore.load().lastSuccessMs

    /** How ingestion is doing, for the launcher's settings. */
    fun state(): IngestState = stateStore.load()

    suspend fun ingest(signal: AmbientSignal): IngestReport {
        if (!enabled()) {
            return IngestReport(signal, ran = false, skippedReason = "ambient ingestion is off", state = IngestState.OFF)
        }

        val now = clock()
        blockedReason(signal, now)?.let { return IngestReport(signal, ran = false, skippedReason = it) }

        // The check above is advisory across threads; the one inside the lock is the real
        // one. Without it, a burst of notifications all pass the check and then queue on
        // the mutex, and every one of them runs.
        return lock.withLock {
            val insideNow = clock()
            if (blockedReason(signal, insideNow) != null) {
                return@withLock IngestReport(signal, ran = false, skippedReason = "another ingest had just run")
            }
            runIngest(signal, insideNow)
        }
    }

    /**
     * Why [signal] may not run now, or null. The cooldown counts from the last *success*, so a
     * failed fetch does not buy six quiet hours; a failure only holds retries back for
     * [AmbientTriggerPolicy.FAILURE_BACKOFF_MS], so a flapping network is not hammered either.
     */
    private fun blockedReason(signal: AmbientSignal, now: Long): String? {
        if (signal == AmbientSignal.MANUAL) return null
        val state = stateStore.load()
        if (state.lastFailureMs > state.lastSuccessMs && now - state.lastFailureMs < AmbientTriggerPolicy.FAILURE_BACKOFF_MS) {
            return "backing off after a failed ingest"
        }
        if (!AmbientTriggerPolicy.shouldIngest(signal, state.lastSuccessMs, now)) return "within the ${signal.name} cooldown"
        return null
    }

    private suspend fun runIngest(signal: AmbientSignal, nowMs: Long): IngestReport {
        val zone = zone()
        val fromMs = nowMs - PredictedContextScorer.maxTailMs
        val toMs = nowMs + CALENDAR_HORIZON_MS
        val problems = mutableListOf<SourceResult.Unavailable>()
        var mailsScanned = 0
        var reservationCount = 0
        var eventCount = 0
        var written = 0

        // 1. The calendars. They are the authority on events: first, and reconciled.
        val calendarKeys = HashSet<String>()
        val calendars = listOfNotNull(GCAL to calendar, deviceCalendar?.let { DEVICE_CALENDAR to it })
        for ((label, source) in calendars) {
            when (val result = attempt { source.fetch(fromMs, toMs, zone) }) {
                is SourceResult.Fetched -> {
                    val events = result.items.distinctBy { it.sourceKey }
                    eventCount += events.size
                    for (event in events) {
                        // A calendar is as current as the moment it was read.
                        val context = PredictedContextMapper.fromCalendarEvent(event, label, observedMs = nowMs) ?: continue
                        contexts.put(context)
                        calendarKeys += event.sourceKey
                        written++
                    }
                    if (result.complete) {
                        val removed = contexts.reconcile(label, fromMs, toMs, events.map { it.sourceKey }.toSet())
                        if (removed > 0) Log.i(TAG, "$label: $removed event(s) gone from the calendar, cards removed")
                    }
                }
                is SourceResult.Unavailable -> problems += result
            }
        }

        // 2. Mail not read before.
        when (val result = attempt { mail.fetch(seen::contains) }) {
            is SourceResult.Fetched -> {
                val messages = result.items
                mailsScanned = messages.size
                val fromMail = ReservationExtractor.extractAll(messages, nowMs, zone)
                reservationCount = fromMail.reservations.size
                for (reservation in fromMail.reservations) {
                    val source = reservation.sourceMessageId?.let { "gmail:$it" } ?: "gmail"
                    val context = PredictedContextMapper.fromReservation(reservation, source, zone) ?: continue
                    contexts.put(context)
                    written++
                }
                // An invitation by mail is a card only where the calendar has not spoken for it:
                // the calendar knows the accepted, current version.
                val invitations = fromMail.calendarEvents.distinctBy { it.sourceKey }.filter { it.sourceKey !in calendarKeys }
                eventCount += invitations.size
                for (event in invitations) {
                    val context = PredictedContextMapper.fromCalendarEvent(event, GMAIL_ICS) ?: continue
                    contexts.put(context)
                    written++
                }
                // Only now, with everything written: a message is done when every part of it was read.
                seen.markAll(messages.filter { it.complete }.map { it.id }, nowMs)
            }
            is SourceResult.Unavailable -> problems += result
        }

        try {
            contexts.purgeExpired(nowMs)
        } catch (e: Exception) {
            org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
            Log.w(TAG, "purge failed: ${e.message}")
        }

        val failed = problems.any { it.problem.transient }
        val stateName = problems.map { it.problem }.let { p ->
            when {
                IngestProblem.AUTH_EXPIRED in p -> IngestState.AUTH_EXPIRED
                p.any { it.transient } -> IngestState.OFFLINE
                IngestProblem.NO_ACCOUNT in p -> IngestState.NO_ACCOUNT
                else -> IngestState.OK
            }
        }
        val previous = stateStore.load()
        stateStore.save(
            previous.copy(
                state = stateName,
                lastAttemptMs = nowMs,
                lastSuccessMs = if (failed) previous.lastSuccessMs else nowMs,
                lastFailureMs = if (failed) nowMs else previous.lastFailureMs,
            )
        )

        Log.i(
            TAG,
            "ingest($signal): $mailsScanned mail(s) -> $reservationCount reservation(s), " +
                "$eventCount event(s), $written context row(s), state=$stateName" +
                (problems.takeIf { it.isNotEmpty() }?.joinToString(prefix = ", problems=") { "${it.problem}:${it.detail}" } ?: ""),
        )
        return IngestReport(
            signal = signal,
            ran = true,
            mailsScanned = mailsScanned,
            reservations = reservationCount,
            calendarEvents = eventCount,
            contextsWritten = written,
            skippedReason = problems.firstOrNull()?.let { it.detail ?: it.problem.name },
            state = stateName,
        )
    }

    /** One source's fetch; a throw is that source failing, never the ingest as a whole. */
    private suspend fun <T> attempt(block: suspend () -> SourceResult<T>): SourceResult<T> = try {
        block()
    } catch (e: Exception) {
        org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
        Log.w(TAG, "source failed: ${e.message}", e)
        SourceResult.Unavailable(IngestProblem.FAILED, e.message)
    }

    companion object {
        private const val TAG = "AmbientIngestor"

        /** The `source` of a row the Google Calendar API wrote. */
        const val GCAL = "gcal"

        /** The `source` of a row the phone's calendar provider wrote. */
        const val DEVICE_CALENDAR = "device-calendar"

        /** The `source` of an invitation that arrived as a mail attachment. */
        const val GMAIL_ICS = "gmail-ics"

        /**
         * How far ahead to pull calendar events.
         *
         * A week. Anything further out scores zero under every kind's lead-in
         * ([PredictedContextScorer]), so fetching it would be a bigger response for rows
         * that cannot surface — and the next ingest will pick them up as they approach.
         */
        private const val CALENDAR_HORIZON_MS = 7L * 24 * 60 * 60 * 1000
    }
}
