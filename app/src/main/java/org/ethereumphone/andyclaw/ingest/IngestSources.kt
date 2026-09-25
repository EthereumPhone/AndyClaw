package org.ethereumphone.andyclaw.ingest

import java.time.ZoneId

/**
 * Why a source had nothing to give. Each maps to what the launcher can tell the user —
 * `IngestState.state` — and to whether the failure should hold the next attempt back.
 */
enum class IngestProblem(val state: String, val transient: Boolean) {
    /** No Google account connected. Not a failure: nothing to fetch, nothing to retry. */
    NO_ACCOUNT(IngestState.NO_ACCOUNT, transient = false),

    /** The account's grant was revoked or expired; only reconnecting fixes it. */
    AUTH_EXPIRED(IngestState.AUTH_EXPIRED, transient = false),

    /** The device calendar is not readable (no calendar permission). Not a network failure. */
    NO_PERMISSION(IngestState.OK, transient = false),

    /** No network, or the request timed out. Worth another try soon. */
    OFFLINE(IngestState.OFFLINE, transient = true),

    /** The server answered with an error. Also worth another try, later. */
    FAILED(IngestState.OFFLINE, transient = true),
}

/** What a source returned: what it found, or why it could not look. */
sealed interface SourceResult<out T> {
    /** [complete] is false when part of what was asked for could not be read (a page, a message). */
    data class Fetched<T>(val items: T, val complete: Boolean = true) : SourceResult<T>

    data class Unavailable(val problem: IngestProblem, val detail: String? = null) : SourceResult<Nothing>
}

/**
 * Where mail comes from.
 *
 * An interface for one reason: [AmbientIngestor] is orchestration — cooldowns, ordering,
 * deduplication, what gets written — and none of that is testable against a class that
 * opens a socket. `GmailIngestSource` is the only implementation that ships.
 */
fun interface MailSource {
    /**
     * Candidate messages, already decoded to bytes. [alreadySeen] names messages an earlier
     * ingest has fully read; they are not downloaded again. Returns rather than throws.
     */
    suspend fun fetch(alreadySeen: (String) -> Boolean): SourceResult<List<MailMessage>>
}

/** Where calendar events come from. Same reasoning as [MailSource]. */
fun interface CalendarSource {
    suspend fun fetch(fromMs: Long, toMs: Long, zone: ZoneId): SourceResult<List<CalendarEvent>>
}
