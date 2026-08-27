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
 * One ingest at a time. Two concurrent sweeps would fetch the same mail twice and race each
 * other into [PredictedContextRepository.put] for the same `sourceKey` — where one of them
 * would lose its insert to the other's, and the pair would burn two round trips to produce
 * one row.
 */
class AmbientIngestor(
    private val mail: GmailIngestSource,
    private val calendar: CalendarIngestSource,
    private val contexts: PredictedContextRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Gate for the whole feature, read per call so a settings change takes effect at once. */
    private val enabled: () -> Boolean = { true },
) {

    private val lock = Mutex()

    @Volatile
    private var lastIngestMs: Long = 0L

    /** When the last ingest ran, for the ambient surface and for tests. */
    val lastIngestAtMs: Long get() = lastIngestMs

    suspend fun ingest(signal: AmbientSignal): IngestReport {
        if (!enabled()) {
            return IngestReport(signal, ran = false, skippedReason = "ambient ingestion is off")
        }

        val now = clock()
        if (!AmbientTriggerPolicy.shouldIngest(signal, lastIngestMs, now)) {
            return IngestReport(signal, ran = false, skippedReason = "within the ${signal.name} cooldown")
        }

        // The check above is advisory across threads; the one inside the lock is the real
        // one. Without it, a burst of notifications all pass the check and then queue on
        // the mutex, and every one of them runs.
        return lock.withLock {
            val insideNow = clock()
            if (!AmbientTriggerPolicy.shouldIngest(signal, lastIngestMs, insideNow)) {
                return@withLock IngestReport(signal, ran = false, skippedReason = "another ingest had just run")
            }
            lastIngestMs = insideNow
            runIngest(signal, insideNow)
        }
    }

    private suspend fun runIngest(signal: AmbientSignal, nowMs: Long): IngestReport {
        var mailsScanned = 0
        var reservationCount = 0
        var eventCount = 0
        var written = 0

        try {
            val messages = mail.fetch()
            mailsScanned = messages.size
            val fromMail = ReservationExtractor.extractAll(messages, nowMs, zone)
            reservationCount = fromMail.reservations.size

            for (reservation in fromMail.reservations) {
                val source = "gmail"
                val context = PredictedContextMapper.fromReservation(reservation, source) ?: continue
                contexts.put(context)
                written++
            }

            // Events attached to mail as `.ics`, plus the calendar itself. Both go through
            // the same mapper, so an invitation and the accepted event converge on one card.
            val events = fromMail.calendarEvents + calendar.fetch(
                fromMs = nowMs - PredictedContextScorer.maxTailMs,
                toMs = nowMs + CALENDAR_HORIZON_MS,
                zone = zone,
            )
            eventCount = events.size
            for (event in events.distinctBy { it.sourceKey }) {
                val context = PredictedContextMapper.fromCalendarEvent(event, "gcal") ?: continue
                contexts.put(context)
                written++
            }

            contexts.purgeExpired(nowMs)
        } catch (e: Exception) {
            Log.w(TAG, "ingest failed: ${e.message}", e)
            return IngestReport(
                signal = signal,
                ran = true,
                mailsScanned = mailsScanned,
                reservations = reservationCount,
                calendarEvents = eventCount,
                contextsWritten = written,
                skippedReason = e.message,
            )
        }

        Log.i(
            TAG,
            "ingest($signal): $mailsScanned mail(s) -> $reservationCount reservation(s), " +
                "$eventCount event(s), $written context row(s)",
        )
        return IngestReport(
            signal = signal,
            ran = true,
            mailsScanned = mailsScanned,
            reservations = reservationCount,
            calendarEvents = eventCount,
            contextsWritten = written,
        )
    }

    companion object {
        private const val TAG = "AmbientIngestor"

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
