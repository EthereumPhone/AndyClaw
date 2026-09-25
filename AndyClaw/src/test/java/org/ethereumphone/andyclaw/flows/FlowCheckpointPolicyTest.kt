package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowCheckpointPolicyTest {

    @Test
    fun `an approved call may cross, whoever asked`() {
        for (p in Provenance.values()) assertTrue(FlowCheckpointPolicy.mayCross(true, noConfirm = false, provenance = p))
    }

    @Test
    fun `without an approval, only no-confirm and the user's own request cross`() {
        assertFalse("confirmations on and no card ran", FlowCheckpointPolicy.mayCross(false, noConfirm = false, provenance = Provenance.USER))
        assertTrue(FlowCheckpointPolicy.mayCross(false, noConfirm = true, provenance = Provenance.USER))
        assertTrue(FlowCheckpointPolicy.mayCross(false, noConfirm = true, provenance = Provenance.TRUSTED))
        assertFalse("a message never crosses unconfirmed", FlowCheckpointPolicy.mayCross(false, noConfirm = true, provenance = Provenance.UNTRUSTED))
    }
}
