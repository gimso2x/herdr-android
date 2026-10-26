package com.gimso2x.herdr.watch

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushEventTest {
    private val origin = "https://fixture.example"
    private val now = 1_800_000_000_000L
    private fun data(id: String = UUID.randomUUID().toString()) = mapOf("type" to "herdr_event",
        "event_id" to id, "kind" to "needs_input", "pane_id" to "qa:p1", "server_origin" to origin)
    private fun event(sent: Long = now, pane: String = "qa:p1") =
        PushEvent(UUID.randomUUID().toString(), AlertKind.NEEDS_INPUT, pane, origin, sent)

    @Test fun validatesTheExactDataOnlyEnvelopeAndBothKinds() {
        assertEquals(AlertKind.NEEDS_INPUT, PushEvent.decode(data(), now, now, origin)?.kind)
        assertEquals(AlertKind.FINISHED, PushEvent.decode(data() + ("kind" to "finished"), now, now, origin)?.kind)
        for (replacement in listOf("kind" to "blocked", "event_id" to "bad", "event_id" to "1-1-1-1-1",
            "pane_id" to "", "pane_id" to "p\n1", "type" to "other", "server_origin" to "https://wrong.example")) {
            assertNull(PushEvent.decode(data() + replacement, now, now, origin))
        }
        assertNull(PushEvent.decode(data() + ("title" to "private"), now, now, origin))
        assertNull(PushEvent.decode(data() - "kind", now, now, origin))
    }

    @Test fun expiredMissingAndFutureTransportTimesAreRejected() {
        assertNull(PushEvent.decode(data(), now - PushEvent.TTL_MILLIS, now, origin))
        assertNull(PushEvent.decode(data(), 0, now, origin))
        assertNull(PushEvent.decode(data(), now + 60_001, now, origin))
        assertNotNull(PushEvent.decode(data(), now - PushEvent.TTL_MILLIS + 1, now, origin))
    }

    @Test fun duplicateAndOutOfOrderDeliveriesCannotReplaceCurrentEvent() {
        val ledger = PushEventLedger()
        val first = event(now - 10)
        val next = event()
        assertTrue(ledger.accept(first, now))
        assertFalse(ledger.accept(first, now))
        assertTrue(ledger.accept(next, now))
        assertFalse(ledger.isCurrent(first))
        assertTrue(ledger.isCurrent(next))
        assertFalse(ledger.accept(event(now - 1), now))
        assertTrue(ledger.accept(event(now - 50, "qa:p2"), now))
        assertTrue(ledger.isCurrent(next))
        val restored = PushEventLedger(ledger.all().associateByTo(linkedMapOf()) { it.eventId })
        assertFalse(restored.accept(next, now))
        assertTrue(restored.isCurrent(next))
    }

    @Test fun equalTimestampDistinctEventsStillReplaceAndLedgerIsBounded() {
        val ledger = PushEventLedger()
        val first = event()
        val next = event()
        assertTrue(ledger.accept(first, now))
        assertTrue(ledger.accept(next, now))
        assertFalse(ledger.isCurrent(first))
        assertTrue(ledger.isCurrent(next))
        repeat(1001) { assertTrue(ledger.accept(event(pane = "qa:$it"), now)) }
        assertEquals(1000, ledger.all().size)
        ledger.accept(event(now + PushEvent.TTL_MILLIS), now + PushEvent.TTL_MILLIS)
        assertEquals(1, ledger.all().size)
    }

    @Test fun detailMachineIdIsReadWhenWellFormedElseLocal() {
        val e = event()
        fun body(extra: String) =
            """{"event_id":"${e.eventId}","pane_id":"${e.paneId}","server_origin":"$origin","kind":"needs_input","created_at":$now$extra}"""
        assertEquals("fixture-m1", PushDetail.parse(body(",\"machine_id\":\"fixture-m1\""), e, now)!!.machineId)
        assertEquals("local", PushDetail.parse(body(""), e, now)!!.machineId)
        assertEquals("local", PushDetail.parse(body(",\"machine_id\":null"), e, now)!!.machineId)
        assertEquals("local", PushDetail.parse(body(",\"machine_id\":\"local\""), e, now)!!.machineId)
        for (bad in listOf("\"\"", "\"a/b\"", "\"a b\"", "\"x&y\"", "\"${"m".repeat(129)}\"", "12", "true", "{}")) {
            assertEquals(bad, "local", PushDetail.parse(body(",\"machine_id\":$bad"), e, now)!!.machineId)
        }
        assertEquals("m".repeat(128), PushDetail.parse(body(",\"machine_id\":\"${"m".repeat(128)}\""), e, now)!!.machineId)
    }

    @Test fun fcmEnvelopeStillRejectsAMachineField() {
        assertNull(PushEvent.decode(data() + ("machine_id" to "fixture-m1"), now, now, origin))
    }

    @Test fun authenticatedDetailMustMatchIdentityOriginKindAndAge() {
        val e = event()
        fun body(id: String = e.eventId, pane: String = e.paneId, host: String = origin,
            kind: String = "needs_input", created: Long = now) =
            """{"event_id":"$id","pane_id":"$pane","server_origin":"$host","kind":"$kind","created_at":$created,"workspace":"fixture","tab":"main","agent":"omp","pane_title":"Fixture task","detail":"Proceed?\nmore"}"""
        val alert = PushDetail.parse(body(), e, now)!!
        assertEquals("fixture · main", alert.title)
        assertEquals("omp · Fixture task — 입력이 필요합니다\nProceed?", alert.bigText)
        assertNull(PushDetail.parse(body(id = UUID.randomUUID().toString()), e, now))
        assertNull(PushDetail.parse(body(pane = "qa:other"), e, now))
        assertNull(PushDetail.parse(body(host = "https://other.example"), e, now))
        assertNull(PushDetail.parse(body(kind = "finished"), e, now))
        assertNull(PushDetail.parse(body(created = now - PushEvent.TTL_MILLIS), e, now))
        assertNull(PushDetail.parse(body(created = now + 60_001), e, now))
        assertNull(PushDetail.parse("not json", e, now))
    }
}
