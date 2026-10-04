package org.ethereumphone.andyclaw.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen keeps one conversation for days: self-contained is about the words. */
class ReflexTurnTest {
    private fun ok(text: String, previous: String? = "Bluetooth is on.") = ReflexTurn.isSelfContained(text, previous)

    @Test fun commandsInALongConversationAreSelfContained() {
        assertTrue(ok("wifi off"))
        assertTrue(ok("too loud"))
        assertTrue(ok("wake me up at half six"))
        assertTrue(ok("open telegram", previous = null))
        assertTrue(ok("what's on my calendar tomorrow"))
    }

    @Test fun messagesThatPointBackAreNot() {
        assertFalse(ok("do it at 8 instead"))
        assertFalse(ok("turn it off"))
        assertFalse(ok("yes"))
        assertFalse(ok("same for bluetooth"))
        assertFalse(ok("again"))
        assertFalse(ok("OK go ahead"))
    }

    @Test fun anAnswerToAQuestionIsNot() {
        assertFalse(ok("7", previous = "What time should the alarm ring?"))
        assertFalse(ok("wifi off", previous = "Which network do you mean?  "))
    }
}
