package org.ethereumphone.andyclaw.safety

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TriggerProvenanceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a job fires with the provenance it was created under`() {
        val created = TriggerProvenanceStore(tmp.root)
        created.record(TriggerProvenanceStore.cronKey(7), Provenance.UNTRUSTED)
        created.record(TriggerProvenanceStore.reminderKey(8), Provenance.USER)

        // The service that fires it holds its own instance over the same file.
        val firing = TriggerProvenanceStore(tmp.root)
        assertEquals(Provenance.UNTRUSTED, firing.provenanceFor(TriggerProvenanceStore.cronKey(7)))
        assertEquals(Provenance.USER, firing.provenanceFor(TriggerProvenanceStore.reminderKey(8)))
    }

    @Test
    fun `a job with no recorded creator runs as untrusted`() {
        // Older than the store — when a stranger's message could create one — or its entry lost:
        // either way nobody can vouch for it.
        val s = TriggerProvenanceStore(tmp.root)
        assertNull(s.of(TriggerProvenanceStore.cronKey(1)))
        assertEquals(Provenance.UNTRUSTED, s.provenanceFor(TriggerProvenanceStore.cronKey(1)))
    }

    @Test
    fun `an unreadable store vouches for nobody`() {
        java.io.File(tmp.root, TriggerProvenanceStore.FILENAME).writeText("{not json")
        val s = TriggerProvenanceStore(tmp.root)
        assertEquals(Provenance.UNTRUSTED, s.provenanceFor(TriggerProvenanceStore.cronKey(2)))
    }

    @Test
    fun `a cancelled job is forgotten`() {
        val s = TriggerProvenanceStore(tmp.root)
        s.record(TriggerProvenanceStore.cronKey(3), Provenance.UNTRUSTED)
        s.forget(TriggerProvenanceStore.cronKey(3))
        assertNull(s.of(TriggerProvenanceStore.cronKey(3)))
    }

    @Test
    fun `the owner's own job fires as a background task, a stranger's stays untrusted`() {
        val s = TriggerProvenanceStore(tmp.root)
        s.record(TriggerProvenanceStore.cronKey(10), Provenance.USER)
        s.record(TriggerProvenanceStore.cronKey(11), Provenance.UNTRUSTED)
        assertEquals(Provenance.TRUSTED, s.firedProvenanceFor(TriggerProvenanceStore.cronKey(10)))
        assertEquals(Provenance.UNTRUSTED, s.firedProvenanceFor(TriggerProvenanceStore.cronKey(11)))
        assertEquals(Provenance.UNTRUSTED, s.firedProvenanceFor(TriggerProvenanceStore.cronKey(12)))
    }

    @Test
    fun `a job created after reading someone else's words is recorded as untrusted`() {
        assertEquals(Provenance.UNTRUSTED, TriggerProvenanceStore.creatorProvenance(Provenance.USER, true))
        assertEquals(Provenance.UNTRUSTED, TriggerProvenanceStore.creatorProvenance(Provenance.TRUSTED, true))
        assertEquals(Provenance.USER, TriggerProvenanceStore.creatorProvenance(Provenance.USER, false))
    }
}
