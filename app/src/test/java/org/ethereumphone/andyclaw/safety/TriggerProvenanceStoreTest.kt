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
    fun `a job older than the store keeps running as it always did`() {
        val s = TriggerProvenanceStore(tmp.root)
        assertNull(s.of(TriggerProvenanceStore.cronKey(1)))
        assertEquals(Provenance.TRUSTED, s.provenanceFor(TriggerProvenanceStore.cronKey(1)))
    }

    @Test
    fun `a cancelled job is forgotten`() {
        val s = TriggerProvenanceStore(tmp.root)
        s.record(TriggerProvenanceStore.cronKey(3), Provenance.UNTRUSTED)
        s.forget(TriggerProvenanceStore.cronKey(3))
        assertNull(s.of(TriggerProvenanceStore.cronKey(3)))
    }
}
