package `in`.wishkaro.app.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The claim/draw ordering — the whole of FR-REM-02, and the half of it that was wrong.
 *
 * Two paths deliver the same reminder: the server's push and the phone's own alarm. The user
 * must see exactly one notification, and a reminder that could NOT be shown must stay
 * deliverable.
 */
class ReminderDeliveryTest {

    private val NOW = Instant.parse("2026-12-02T03:30:00Z")

    private fun reminder(key: String = "e1:2026:D0") = DeliverableReminder(
        dedupeKey = key,
        title = "Ravi's birthday is today",
        body = "They turn 36 today.",
        offset = "D0",
        type = "REMINDER",
        personId = "p1",
        personName = "Ravi",
        sentiment = "CELEBRATORY",
        deepLink = "wishkaro://person/p1",
    )

    private fun delivery(store: FakeDeliveryStore, canDraw: () -> Boolean) =
        ReminderDelivery(store, { canDraw() }, now = { NOW })

    @Test
    fun drawsAndRecordsTheFirstArrival() = runTest {
        val store = FakeDeliveryStore()

        val outcome = delivery(store) { true }.deliver(reminder(), DeliveryChannel.LOCAL)

        assertEquals(DeliveryOutcome.SHOWN, outcome)
        assertEquals(listOf("e1:2026:D0" to DeliveryOutcome.SHOWN), store.reports)
        assertTrue(store.claimed.contains("e1:2026:D0"))
    }

    /** The second path to arrive sees one notification already on screen and stands down. */
    @Test
    fun suppressesTheSecondArrival() = runTest {
        val store = FakeDeliveryStore()
        delivery(store) { true }.deliver(reminder(), DeliveryChannel.LOCAL)

        val outcome = delivery(store) { true }.deliver(reminder(), DeliveryChannel.FCM)

        assertEquals(DeliveryOutcome.SUPPRESSED_DUPLICATE, outcome)
        assertEquals(1, store.drawnCount)
    }

    /**
     * The bug. Permission is not granted at 09:00, so nothing is drawn — and the claim used
     * to stay, for ever. The catch-up pass in `rearmAll()` exists precisely to deliver a
     * reminder whose moment passed while the app was dead, and it would take the
     * already-seen branch and suppress it as a duplicate. Granting the permission half an
     * hour later never brought it back; it did not come round again for a year.
     */
    @Test
    fun aBlockedReminderStaysDeliverable() = runTest {
        val store = FakeDeliveryStore()

        val blocked = delivery(store) { false }.deliver(reminder(), DeliveryChannel.FCM)
        assertEquals(DeliveryOutcome.BLOCKED_NO_PERMISSION, blocked)
        assertFalse(
            store.claimed.contains("e1:2026:D0"),
            "the key is still claimed, so this reminder can never be delivered again",
        )

        // The user grants the permission and the catch-up fires.
        val later = delivery(store) { true }.deliver(reminder(), DeliveryChannel.LOCAL)

        assertEquals(DeliveryOutcome.SHOWN, later)
        assertEquals(1, store.drawnCount)
    }

    /**
     * And the self-test has to be able to tell the two apart. `wasReminderSeen()` reads the
     * same claim table, so a claim left behind by a blocked notification would make FR-REM-07
     * report delivery on exactly the phones whose blocking it exists to diagnose.
     */
    @Test
    fun aBlockedReminderIsNotRecordedAsSeen() = runTest {
        val store = FakeDeliveryStore()

        delivery(store) { false }.deliver(reminder("test:u1:123"), DeliveryChannel.FCM)

        assertFalse(store.claimed.contains("test:u1:123"))
        assertEquals(
            listOf("test:u1:123" to DeliveryOutcome.BLOCKED_NO_PERMISSION),
            store.reports,
        )
    }

    /** A blocked attempt must not let a racing path draw a second notification either. */
    @Test
    fun releasingDoesNotOpenTheDoorToADouble() = runTest {
        val store = FakeDeliveryStore()

        delivery(store) { false }.deliver(reminder(), DeliveryChannel.FCM)
        delivery(store) { true }.deliver(reminder(), DeliveryChannel.LOCAL)
        delivery(store) { true }.deliver(reminder(), DeliveryChannel.FCM)

        assertEquals(1, store.drawnCount)
    }

    @Test
    fun everyAttemptIsReportedEvenWhenNothingIsDrawn() = runTest {
        val store = FakeDeliveryStore()

        delivery(store) { true }.deliver(reminder(), DeliveryChannel.LOCAL)
        delivery(store) { true }.deliver(reminder(), DeliveryChannel.FCM)

        // A suppressed duplicate is the de-duplication working, and the server has to be able
        // to tell it apart from a push that never arrived.
        assertEquals(
            listOf(
                "e1:2026:D0" to DeliveryOutcome.SHOWN,
                "e1:2026:D0" to DeliveryOutcome.SUPPRESSED_DUPLICATE,
            ),
            store.reports,
        )
    }
}

private class FakeDeliveryStore : DeliveryStore {
    val claimed = mutableSetOf<String>()
    val reports = mutableListOf<Pair<String, String>>()
    var drawnCount = 0
        private set

    /** Mirrors `INSERT OR IGNORE`: true the first time, false while the claim is held. */
    override suspend fun claimReminder(dedupeKey: String, nowIso: String): Boolean =
        claimed.add(dedupeKey)

    override suspend fun releaseReminderClaim(dedupeKey: String) {
        claimed.remove(dedupeKey)
    }

    override suspend fun recordDelivery(
        dedupeKey: String,
        outcome: String,
        channel: String,
        occurredAtIso: String,
    ) {
        reports += dedupeKey to outcome
        if (outcome == DeliveryOutcome.SHOWN) drawnCount += 1
    }
}
