package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowRunAccountingTest {

    private fun aborted(reason: FlowAbortReason, committed: Boolean = false) =
        FlowRunResult.Aborted(reason, "x", 0, 10, emptyList(), committed)

    @Test
    fun `only the flow's own faults count against it`() {
        for (reason in listOf(FlowAbortReason.STOPPED, FlowAbortReason.MISSING_PARAM, FlowAbortReason.CHECKPOINT_REFUSED,
            FlowAbortReason.DISPLAY_UNAVAILABLE, FlowAbortReason.APP_NOT_INSTALLED)) {
            assertFalse("$reason", FlowRunAccounting.counts(aborted(reason)))
        }
        for (reason in listOf(FlowAbortReason.CHECKSUM_MISMATCH, FlowAbortReason.ASSERT_FAILED, FlowAbortReason.STEP_FAILED,
            FlowAbortReason.AMBIGUOUS_TARGET, FlowAbortReason.SENSITIVE_TARGET)) {
            assertTrue("$reason", FlowRunAccounting.counts(aborted(reason)))
        }
        assertTrue(FlowRunAccounting.counts(FlowRunResult.Completed(3, 10, emptyList())))
    }

    @Test
    fun `nothing that may already have happened is done a second time`() {
        assertFalse(FlowRunAccounting.mayFallBack(aborted(FlowAbortReason.ASSERT_FAILED, committed = true)))
        assertFalse(FlowRunAccounting.mayFallBack(aborted(FlowAbortReason.DISPLAY_UNAVAILABLE, committed = true)))
        assertTrue(FlowRunAccounting.mayFallBack(aborted(FlowAbortReason.ASSERT_FAILED)))
    }

    @Test
    fun `a no is not answered by trying another way`() {
        for (reason in listOf(FlowAbortReason.STOPPED, FlowAbortReason.CHECKPOINT_REFUSED, FlowAbortReason.SENSITIVE_TARGET,
            FlowAbortReason.MISSING_PARAM)) {
            assertFalse("$reason", FlowRunAccounting.mayFallBack(aborted(reason)))
        }
        assertTrue(FlowRunAccounting.mayFallBack(aborted(FlowAbortReason.CHECKSUM_MISMATCH)))
        assertTrue(FlowRunAccounting.mayFallBack(aborted(FlowAbortReason.AMBIGUOUS_TARGET)))
    }
}
