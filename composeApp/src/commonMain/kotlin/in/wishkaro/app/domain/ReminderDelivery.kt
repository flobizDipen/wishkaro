package `in`.wishkaro.app.domain

import kotlin.time.Clock
import kotlin.time.Instant

/** Where a reminder came from, and which `scheduled_reminder.source` owns it. */
object ReminderSource {
    /** The server's plan. A refresh deletes any row the latest plan no longer contains. */
    const val PLAN = "PLAN"

    /** Computed on-device for an event the server has never seen. Withdrawn once it syncs. */
    const val LOCAL_FALLBACK = "LOCAL_FALLBACK"

    /**
     * Written by the user tapping **Snooze 1h** (FR-REM-04).
     *
     * A third value rather than a flag on [LOCAL_FALLBACK], because both reconciliations work
     * by deleting whatever they do not currently want — and a snooze is by definition not
     * wanted by either of them. It was asked for.
     */
    const val SNOOZE = "SNOOZE"
}

/** Everything the delivery path needs to say about what happened (FR-REM-10). */
object DeliveryOutcome {
    const val SHOWN = "SHOWN"
    const val SUPPRESSED_DUPLICATE = "SUPPRESSED_DUPLICATE"
    const val BLOCKED_NO_PERMISSION = "BLOCKED_NO_PERMISSION"
}

/** Which path got there. */
object DeliveryChannel {
    const val FCM = "FCM"
    const val LOCAL = "LOCAL"
}

/** A reminder ready to be drawn. Everything here was rendered by the server. */
data class DeliverableReminder(
    val dedupeKey: String,
    val title: String,
    val body: String,
    val offset: String,
    val type: String,
    val personId: String?,
    val personName: String?,
    val sentiment: String?,
    val deepLink: String?,
)

/** Draws the notification. False means it could not be shown — missing permission. */
fun interface ReminderNotifier {
    fun draw(reminder: DeliverableReminder): Boolean
}

/** The slice of the local database the delivery path touches. */
interface DeliveryStore {
    suspend fun claimReminder(dedupeKey: String, nowIso: String): Boolean
    suspend fun releaseReminderClaim(dedupeKey: String)
    suspend fun recordDelivery(dedupeKey: String, outcome: String, channel: String, occurredAtIso: String)
}

/**
 * Claims a reminder, draws it, and records what happened — once, whichever path delivered it.
 *
 * In common code rather than in the Android presenter because this ordering is the whole of
 * FR-REM-02 and it has to be testable without a device. The Android side keeps what is
 * genuinely Android: channels, action buttons, `NotificationManagerCompat`.
 *
 * ## The order, and why it is this one
 *
 * Two paths can arrive at the same reminder at the same moment — the server's push and the
 * phone's own alarm — and the user must see exactly one notification.
 *
 * **Claim first, then draw, and give the claim back if the draw failed.** The alternative,
 * drawing first and claiming afterwards, loses the race outright: both paths would draw
 * before either claimed. Claiming first means at most one path is ever in the drawing state.
 *
 * The cost is a window — the duration of [ReminderNotifier.draw] — in which the key is
 * claimed and nothing is on screen. If the process dies inside it the reminder is lost. That
 * window is milliseconds, and it replaces the previous behaviour, where a reminder that
 * could not be drawn stayed claimed **for ever**: the catch-up pass in `rearmAll()` would
 * then suppress it as a duplicate, so granting the notification permission half an hour later
 * never brought it back and it did not come round again for a year.
 *
 * Releasing also keeps the self-test honest. `wasReminderSeen()` is what FR-REM-07 polls, so
 * a claim with nothing drawn reports a blocked notification as delivered — on exactly the
 * phones whose blocking that feature exists to diagnose.
 */
class ReminderDelivery(
    private val store: DeliveryStore,
    private val notifier: ReminderNotifier,
    private val now: () -> Instant = Clock.System::now,
) {

    suspend fun deliver(reminder: DeliverableReminder, channel: String): String {
        val nowIso = now().toString()

        val claimed = store.claimReminder(reminder.dedupeKey, nowIso)
        if (!claimed) {
            // The other path got there first. This is the de-duplication WORKING, and the
            // server records it as such rather than as a failed delivery.
            store.recordDelivery(
                reminder.dedupeKey,
                DeliveryOutcome.SUPPRESSED_DUPLICATE,
                channel,
                nowIso,
            )
            return DeliveryOutcome.SUPPRESSED_DUPLICATE
        }

        val drawn = notifier.draw(reminder)
        if (!drawn) {
            // Nothing is on screen, so nothing has been "seen". Give the key back, or this
            // reminder can never be delivered again.
            store.releaseReminderClaim(reminder.dedupeKey)
            store.recordDelivery(
                reminder.dedupeKey,
                DeliveryOutcome.BLOCKED_NO_PERMISSION,
                channel,
                nowIso,
            )
            return DeliveryOutcome.BLOCKED_NO_PERMISSION
        }

        store.recordDelivery(reminder.dedupeKey, DeliveryOutcome.SHOWN, channel, nowIso)
        return DeliveryOutcome.SHOWN
    }
}

/**
 * Where a notification's **Send wish** / **Send message** action should land (FR-WSH-01).
 *
 * M2 shipped both buttons pointing at `wishkaro://person/<id>` — the same link as tapping
 * the notification body — because the composer did not exist. M5 is what they were waiting
 * for, and the labels and placement are already decided (FR-REM-04, FR-REM-11): the solemn
 * ladder says "Send message" and must not regress to "Send wish".
 *
 * The event id is recovered from the DEDUPE KEY rather than added to the push payload.
 * `dedupeKeyFor` is `${eventId}:${occurrenceYear}:${offset}` and both sides already compute
 * it — see `dedupe-key.ts` and M2's rewrite migration. Taking it from there means no new
 * field on the wire, no server change, and no second source of truth for which event a
 * reminder is about. A key that does not parse yields a link with no event, which the
 * composer resolves to the person's next occasion — the same thing the person screen's own
 * button does.
 *
 * Pure, so the parsing is tested without a device: the Intent around it is three lines.
 */
fun composerDeepLink(reminder: DeliverableReminder): String? {
    val personId = reminder.personId ?: return null
    // A self-test or a digest is about nobody in particular and offers no action anyway.
    if (reminder.type == "TEST" || reminder.type == "DIGEST") return null

    val eventId = eventIdFromDedupeKey(reminder.dedupeKey)
    return if (eventId != null) {
        "wishkaro://wish/$personId?event=$eventId"
    } else {
        "wishkaro://wish/$personId"
    }
}

/**
 * The event id inside a reminder's dedupe key, or null.
 *
 * Deliberately strict about the shape. A snooze, a self-test (`test:${user}:${ms}`) and
 * anything a newer server invents all fail to parse here, and the caller degrades to "the
 * next occasion" rather than building a link to an event id that is really a timestamp.
 */
internal fun eventIdFromDedupeKey(dedupeKey: String): String? {
    val parts = dedupeKey.split(':')
    if (parts.size != 3) return null
    val (eventId, year, _) = parts
    if (eventId.isBlank() || eventId == "test") return null
    // The middle segment is an occurrence YEAR. If it is not, this is not the key shape
    // this function understands, whatever it looks like.
    if (year.toIntOrNull() == null) return null
    return eventId
}
