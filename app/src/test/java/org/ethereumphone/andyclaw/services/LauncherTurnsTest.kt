package org.ethereumphone.andyclaw.services

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.util.Collections

/** IPC-05, IPC-06 and CHAT-02: how a launcher turn starts, is replaced, dies and ends. */
class LauncherTurnsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** A callback binder whose death the test decides. */
    private class FakeBinder(private val dead: Boolean = false) : IBinder {
        @Volatile var recipient: IBinder.DeathRecipient? = null
        @Volatile var unlinked = false

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (dead) throw RemoteException()
            this.recipient = recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            unlinked = true
            return this.recipient === recipient
        }

        fun die() = recipient!!.binderDied()

        override fun getInterfaceDescriptor(): String? = null
        override fun pingBinder(): Boolean = !dead
        override fun isBinderAlive(): Boolean = !dead
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    @Test
    fun `a turn whose launcher died is cancelled`() = runBlocking {
        val turns = LauncherTurns(scope)
        val binder = FakeBinder()
        val running = CompletableDeferred<Unit>()
        val job = turns.start("L1", binder) {
            running.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { running.await() }
        assertNotNull("the turn links to its caller's death", binder.recipient)

        binder.die()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue("the link is removed when the turn ends", binder.unlinked)
        assertFalse(turns.isRunning("L1"))
    }

    @Test
    fun `a launcher already dead cancels its turn before it does anything`() = runBlocking {
        val turns = LauncherTurns(scope)
        var bodyRan = false
        val job = turns.start("L1", FakeBinder(dead = true)) { bodyRan = true }
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertFalse(bodyRan)
    }

    @Test
    fun `a turn that ends normally unlinks and leaves nothing behind`() = runBlocking {
        val turns = LauncherTurns(scope)
        val binder = FakeBinder()
        val job = turns.start("L1", binder) {}
        withTimeout(5_000) { job.join() }
        assertFalse(job.isCancelled)
        assertTrue(binder.unlinked)
        assertFalse(turns.isRunning("L1"))
    }

    @Test
    fun `a second prompt cancels the first and waits for it to stop before running`() = runBlocking {
        val turns = LauncherTurns(scope)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstRunning = CompletableDeferred<Unit>()
        val first = turns.start("L1", null) {
            try {
                firstRunning.complete(Unit)
                awaitCancellation()
            } finally {
                events += "first stopped"
            }
        }
        withTimeout(5_000) { firstRunning.await() }

        val secondRunning = CompletableDeferred<Unit>()
        val second = turns.start("L1", null) {
            events += "second started"
            secondRunning.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { secondRunning.await() }
        assertTrue(first.isCancelled)
        assertEquals(listOf("first stopped", "second started"), events.toList())

        // STOP reaches the turn that is running now, not the one it replaced.
        assertTrue(turns.stop("L1"))
        withTimeout(5_000) { second.join() }
        assertTrue(second.isCancelled)
        assertFalse(turns.stop("L1"))
    }

    @Test
    fun `turns of different sessions do not touch each other`() = runBlocking {
        val turns = LauncherTurns(scope)
        val a = turns.start("A", null) { awaitCancellation() }
        val b = turns.start("B", null) { awaitCancellation() }
        assertTrue(turns.stop("A"))
        withTimeout(5_000) { a.join() }
        assertTrue(b.isActive)
        assertTrue(turns.stop("B"))
    }

    @Test
    fun `exactly one terminal callback goes out`() {
        val terminal = TurnTerminal()
        val sent = mutableListOf<String>()
        assertTrue(terminal.end { sent += "error" })
        assertFalse(terminal.end { sent += "complete" })
        assertFalse(terminal.end { sent += "cancelled" })
        assertEquals(listOf("error"), sent)
        assertTrue(terminal.ended)
    }
}
