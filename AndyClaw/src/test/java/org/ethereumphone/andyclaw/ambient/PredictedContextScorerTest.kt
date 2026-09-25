package org.ethereumphone.andyclaw.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time-to-relevance: the curve the ambient surface orders itself by. */
class PredictedContextScorerTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun ctx(
        kind: PredictedKind,
        startsInMs: Long,
        durationMs: Long? = null,
        dismissed: Boolean = false,
    ) = PredictedContext(
        id = "id",
        kind = kind,
        title = kind.name,
        startMs = now + startsInMs,
        endMs = durationMs?.let { now + startsInMs + it },
        sourceKey = "key-${kind.name}-$startsInMs",
        dismissedMs = if (dismissed) now else null,
    )

    @Test
    fun `a thing outside its own lead-in scores nothing`() {
        // Not "a small number" — zero. A flight next week must never outrank a meeting
        // this afternoon just because flights have long lead-ins.
        assertEquals(0.0, PredictedContextScorer.score(ctx(PredictedKind.FLIGHT, 30 * hour), now), 0.0)
        assertEquals(0.0, PredictedContextScorer.score(ctx(PredictedKind.CALENDAR, 3 * hour), now), 0.0)
    }

    @Test
    fun `relevance ramps up across the lead-in`() {
        val far = PredictedContextScorer.score(ctx(PredictedKind.FLIGHT, 20 * hour), now)
        val near = PredictedContextScorer.score(ctx(PredictedKind.FLIGHT, 4 * hour), now)
        val imminent = PredictedContextScorer.score(ctx(PredictedKind.FLIGHT, 10 * 60_000), now)

        assertTrue(far in 0.0..1.0)
        assertTrue(far < near)
        assertTrue(near < imminent)
    }

    @Test
    fun `a thing in progress scores one`() {
        val inProgress = ctx(PredictedKind.EVENT, startsInMs = -30 * 60_000, durationMs = 2 * hour)
        assertEquals(1.0, PredictedContextScorer.score(inProgress, now), 1e-9)
    }

    @Test
    fun `relevance decays across the tail rather than vanishing`() {
        val justStarted = ctx(PredictedKind.CALENDAR, startsInMs = -1_000)
        val tenMinutesAgo = ctx(PredictedKind.CALENDAR, startsInMs = -10 * 60_000)
        val anHourAgo = ctx(PredictedKind.CALENDAR, startsInMs = -60 * 60_000)

        // A zero-length entry is already in its tail one second after it starts, so this
        // is "essentially one", not one — and that is the behaviour worth pinning: the
        // card does not drop the instant the thing begins.
        assertTrue(PredictedContextScorer.score(justStarted, now) > 0.99)
        val decaying = PredictedContextScorer.score(tenMinutesAgo, now)
        assertTrue(decaying > 0.0 && decaying < 1.0)
        assertEquals(0.0, PredictedContextScorer.score(anHourAgo, now), 0.0)
    }

    @Test
    fun `a dismissed card stays dismissed however close it gets`() {
        val imminent = ctx(PredictedKind.FLIGHT, startsInMs = 60_000, dismissed = true)
        assertEquals(0.0, PredictedContextScorer.score(imminent, now), 0.0)
    }

    @Test
    fun `each kind has its own idea of soon`() {
        // Three hours out: a flight is well inside its window, a calendar entry is not
        // inside its at all.
        val flight = PredictedContextScorer.score(ctx(PredictedKind.FLIGHT, 3 * hour), now)
        val meeting = PredictedContextScorer.score(ctx(PredictedKind.CALENDAR, 3 * hour), now)
        assertTrue(flight > 0.0)
        assertEquals(0.0, meeting, 0.0)
    }

    @Test
    fun `ranking puts the most relevant first and breaks ties by start time`() {
        val soon = ctx(PredictedKind.CALENDAR, startsInMs = 15 * 60_000)
        val later = ctx(PredictedKind.CALENDAR, startsInMs = 90 * 60_000)
        val liveNow = ctx(PredictedKind.EVENT, startsInMs = -10 * 60_000, durationMs = hour)
        val alsoLive = ctx(PredictedKind.EVENT, startsInMs = -20 * 60_000, durationMs = hour)

        val ranked = PredictedContextScorer.rank(listOf(later, soon, liveNow, alsoLive), now)

        // Both live things score exactly 1, so the earlier one leads — without the second
        // key their order would be whatever the database returned.
        assertEquals(alsoLive.sourceKey, ranked[0].context.sourceKey)
        assertEquals(liveNow.sourceKey, ranked[1].context.sourceKey)
        assertEquals(soon.sourceKey, ranked[2].context.sourceKey)
        assertEquals(later.sourceKey, ranked[3].context.sourceKey)
    }

    @Test
    fun `ranking drops what is not relevant`() {
        val nextWeek = ctx(PredictedKind.EVENT, startsInMs = 7 * 24 * hour)
        assertTrue(PredictedContextScorer.rank(listOf(nextWeek), now).isEmpty())
    }

    @Test
    fun `untilStart is reported from the same moment the score was computed`() {
        val scored = PredictedContextScorer.scored(ctx(PredictedKind.FLIGHT, 2 * hour), now)
        assertEquals(2 * hour, scored.untilStartMs)
    }

    @Test
    fun `an all-day entry does not bury a flight two hours out`() {
        // A birthday that started at midnight used to score 1 all day, above everything.
        val allDay = ctx(PredictedKind.CALENDAR, startsInMs = -10 * hour, durationMs = 24 * hour)
        val flight = ctx(PredictedKind.FLIGHT, startsInMs = 2 * hour)

        val ranked = PredictedContextScorer.rank(listOf(allDay, flight), now)

        assertEquals(flight.sourceKey, ranked.first().context.sourceKey)
        assertEquals("still shown, below", 2, ranked.size)
    }

    @Test
    fun `a long span is fully relevant only as it begins`() {
        val justBegun = ctx(PredictedKind.LODGING, startsInMs = -30 * 60_000, durationMs = 3 * 24 * hour)
        val midStay = ctx(PredictedKind.LODGING, startsInMs = -30 * hour, durationMs = 3 * 24 * hour)

        assertEquals(1.0, PredictedContextScorer.score(justBegun, now), 1e-9)
        assertEquals(PredictedContextScorer.LONG_SPAN_FLOOR, PredictedContextScorer.score(midStay, now), 1e-9)
    }

    @Test
    fun `a flight known only by its day stays at full relevance all that day`() {
        val dateOnlyFlight = ctx(PredictedKind.FLIGHT, startsInMs = -14 * hour, durationMs = 24 * hour)
        assertEquals(1.0, PredictedContextScorer.score(dateOnlyFlight, now), 1e-9)
    }

    @Test
    fun `a cancelled booking scores nothing`() {
        val cancelled = ctx(PredictedKind.FLIGHT, startsInMs = hour).copy(payloadJson = """{"cancelled":true}""")
        assertEquals(0.0, PredictedContextScorer.score(cancelled, now), 0.0)
    }

    @Test
    fun `the query window covers every kind`() {
        // The repository narrows in SQL using these two; a kind whose lead-in exceeded the
        // window would silently never surface.
        assertTrue(
            PredictedKind.entries.all { PredictedContextScorer.leadInMs(it) <= PredictedContextScorer.maxLeadInMs }
        )
        assertTrue(
            PredictedKind.entries.all { PredictedContextScorer.tailMs(it) <= PredictedContextScorer.maxTailMs }
        )
    }
}
