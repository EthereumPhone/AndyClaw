package org.ethereumphone.andyclaw.ingest

import java.time.ZoneId

/**
 * Where mail comes from.
 *
 * An interface for one reason: [AmbientIngestor] is orchestration — cooldowns, ordering,
 * deduplication, what gets written — and none of that is testable against a class that
 * opens a socket. `GmailIngestSource` is the only implementation that ships.
 */
fun interface MailSource {
    /** Candidate messages, already decoded to bytes. Returns empty rather than throwing. */
    suspend fun fetch(): List<MailMessage>
}

/** Where calendar events come from. Same reasoning as [MailSource]. */
fun interface CalendarSource {
    suspend fun fetch(fromMs: Long, toMs: Long, zone: ZoneId): List<CalendarEvent>
}
