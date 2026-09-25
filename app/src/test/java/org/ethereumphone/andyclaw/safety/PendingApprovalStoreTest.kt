package org.ethereumphone.andyclaw.safety

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A signature key the test holds, standing in for the keystore's. */
internal class TestMac(private val key: ByteArray = "test-key".toByteArray()) : ApprovalMac {
    override fun sign(data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(data))
    }
}

class PendingApprovalStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L

    private fun store(dir: File = tmp.root, mac: ApprovalMac = TestMac(), secret: (String) -> Boolean = { "SECRET" in it }) =
        PendingApprovalStore(dir, mac, clock = { now }, holdsSecret = secret)

    private fun send(to: String = "0xabc", amount: String = "0.05") = buildJsonObject {
        put("recipient_address", to)
        put("amount", amount)
    }

    private fun request(
        input: JsonObject? = send(),
        conversation: String? = "chat-1",
        tool: String = "agent_send_native_token",
        source: String = "telegram",
    ) = PendingApprovalStore.Request(
        source = source,
        provenance = "UNTRUSTED",
        toolName = tool,
        input = input,
        description = "Send from the agent wallet",
        conversationId = conversation,
        ledgerSessionId = "telegram:1",
        effect = "IRREVERSIBLE",
        toolReason = "cannot be undone",
    )

    @Test
    fun `a queued call keeps its exact input and can be claimed once`() {
        val s = store()
        val e = s.queue(request())!!
        assertTrue(s.isExecutable(e))

        val claim = s.claim(e.id)
        assertTrue(claim is PendingApprovalStore.Claim.Claimed)
        assertEquals(send(), (claim as PendingApprovalStore.Claim.Claimed).input)
        assertEquals("a second approve cannot run it again", PendingApprovalStore.Claim.Running, s.claim(e.id))

        s.finish(e.id, "DONE", "Done.")
        assertEquals(PendingApprovalStore.Claim.Missing, s.claim(e.id))
        assertEquals("DONE", s.outcome(e.id)?.state)
    }

    @Test
    fun `the same request again is counted, not added`() {
        val s = store()
        val first = s.queue(request())!!
        val again = s.queue(request())!!
        assertEquals(first.id, again.id)
        assertEquals(2, again.count)
        assertEquals(1, s.getAll().size)
    }

    @Test
    fun `one conversation cannot fill the queue`() {
        val s = store()
        repeat(PendingApprovalStore.MAX_PER_CONVERSATION) { assertNotNull(s.queue(request(send(amount = "$it")))) }
        assertNull(s.queue(request(send(amount = "99"))))
        assertNotNull("another conversation still gets a card", s.queue(request(send(amount = "99"), conversation = "chat-2")))
    }

    @Test
    fun `the queue as a whole is bounded`() {
        val s = store()
        repeat(PendingApprovalStore.MAX_PENDING) { assertNotNull(s.queue(request(conversation = "c$it"))) }
        assertNull(s.queue(request(conversation = "one-more")))
    }

    @Test
    fun `an expired request is finished, not run`() {
        val s = store()
        val e = s.queue(request())!!
        now += PendingApprovalStore.TTL_MS
        assertEquals(PendingApprovalStore.Claim.Expired, s.claim(e.id))
        assertEquals("EXPIRED", s.outcome(e.id)?.state)
        assertTrue(s.getAll().isEmpty())
    }

    @Test
    fun `a request left running by a dead process is never run again`() {
        val first = store()
        val e = first.queue(request())!!
        assertTrue(first.claim(e.id) is PendingApprovalStore.Claim.Claimed)

        // A new process: same files, a fresh store.
        val second = store()
        assertTrue(second.getAll().isEmpty())
        assertEquals(PendingApprovalStore.Claim.Missing, second.claim(e.id))
        assertEquals(PendingApprovalStore.UNKNOWN, second.outcome(e.id)?.state)
    }

    @Test
    fun `a busy display puts the request back for later`() {
        val s = store()
        val e = s.queue(request())!!
        s.claim(e.id)
        assertTrue(s.release(e.id))
        assertTrue(s.claim(e.id) is PendingApprovalStore.Claim.Claimed)
    }

    @Test
    fun `an entry an older build rewrote can be declined but never run`() {
        val s = store()
        val e = s.queue(request())!!
        // An older build knows only the v1 fields and writes back only those.
        val file = File(tmp.root, PendingApprovalStore.FILENAME)
        val v1 = (kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as JsonArray).map { el ->
            val o = el.jsonObject
            JsonObject(o.filterKeys { it in setOf("id", "timestampMs", "source", "provenance", "toolName", "description", "conversationId", "inputPreview") })
        }
        file.writeText(JsonArray(v1).toString())

        val reread = store()
        val entry = reread.getAll().single()
        assertFalse(reread.isExecutable(entry))
        assertEquals(PendingApprovalStore.Claim.NotExecutable, reread.claim(e.id))
        assertTrue(reread.decline(e.id))
    }

    @Test
    fun `a tampered entry is not executable`() {
        val s = store()
        val e = s.queue(request())!!
        val file = File(tmp.root, PendingApprovalStore.FILENAME)
        file.writeText(file.readText().replace("0xabc", "0xevil"))
        val reread = store()
        assertEquals(PendingApprovalStore.Claim.NotExecutable, reread.claim(e.id))
    }

    @Test
    fun `a request signed under another key is not executable`() {
        val e = store(mac = TestMac("old".toByteArray())).queue(request())!!
        assertEquals(PendingApprovalStore.Claim.NotExecutable, store().claim(e.id))
    }

    @Test
    fun `an input with a secret in it is never written down`() {
        val s = store()
        val e = s.queue(request(buildJsonObject { put("code", "print('SECRET')") }, tool = "execute_code"))!!
        assertNull(e.input)
        assertNull(e.inputPreview)
        assertFalse(File(tmp.root, PendingApprovalStore.FILENAME).readText().contains("SECRET"))
        assertEquals(PendingApprovalStore.Claim.NotExecutable, s.claim(e.id))
    }

    @Test
    fun `an input too big to show is kept out`() {
        val big = buildJsonObject { put("message", "x".repeat(PendingApprovalStore.MAX_INPUT_BYTES + 1)) }
        val e = store().queue(request(big, tool = "send_sms"))!!
        assertNull(e.input)
    }

    @Test
    fun `fields a newer build wrote survive this build's rewrite`() {
        val s = store()
        val e = s.queue(request())!!
        val file = File(tmp.root, PendingApprovalStore.FILENAME)
        val withExtra = (kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as JsonArray).map {
            JsonObject(it.jsonObject + ("fromTheFuture" to JsonPrimitive("keep me")))
        }
        file.writeText(JsonArray(withExtra).toString())
        val reread = store()
        reread.release(e.id) // any rewrite
        reread.queue(request(conversation = "other"))
        assertTrue(file.readText().contains("keep me"))
    }

    @Test
    fun `canonical input does not depend on key order`() {
        val a = buildJsonObject { put("b", 1); put("a", 2) }
        val b = buildJsonObject { put("a", 2); put("b", 1) }
        assertEquals(PendingApprovalStore.canonical(a), PendingApprovalStore.canonical(b))
    }
}
