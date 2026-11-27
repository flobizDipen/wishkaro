/**
 * Everything in this system that belongs to a user, written down as data rather than as
 * control flow.
 *
 * `docs/BUILD_PROMPT_M9.md`:
 *
 * > *"Enumerate every table and every store that holds something belonging to a user, write
 * > the list in the plan before writing the deletion, and make the test assert on the absence
 * > of each one rather than on the endpoint returning 204."*
 *
 * The list is in `docs/M9_PLAN.md` §6. This is the same list in a form two things can read:
 * `AccountDeletionService`, which must leave nothing out, and `account-deletion.db.spec.ts`,
 * which asserts on each entry by name.
 *
 * ## Why a table rather than a sequence of statements
 *
 * The failure this guards is not "somebody forgot a table today". It is **M10 adding a table
 * and nobody adding it to the deletion** — which is silent, is discovered by a user, and is
 * the exact shape of the thing the build prompt says is most likely to go wrong.
 *
 * `deletion-coverage.spec.ts` reads Prisma's own DMMF, finds every model carrying a `userId`
 * field, and fails if it is not named here. So a new table is a build failure rather than a
 * leak. That is `MILESTONE_CHECKLIST.md`'s "where a mistake has a mechanical signature, write
 * the grep or the test instead", bought by M6 shipping the same `${'$'}` bug three times
 * after it was already on the list.
 */

/**
 * What happens to a table when its owner is deleted.
 *
 * - `CASCADE` — rows vanish with the user, by the foreign key. The database does it.
 * - `ANONYMISE` — rows stay, `user_id` becomes NULL, by `ON DELETE SET NULL`.
 * - `TRANSITIVE` — no `user_id` at all; reached only through a parent that cascades.
 */
export type Disposition = 'CASCADE' | 'ANONYMISE' | 'TRANSITIVE';

export interface TableEntry {
  /** The Prisma model name, which is what the DMMF guard matches on. */
  model: string;
  /** The Postgres table, which is what the DB spec counts rows in. */
  table: string;
  disposition: Disposition;
  /** What of the user's is in it, in the terms a policy page would have to use. */
  holds: string;
}

export const DELETION_INVENTORY: readonly TableEntry[] = [
  { model: 'User', table: 'user', disposition: 'CASCADE', holds: 'email, name, photo URL, Google sub, timezone, every reminder setting' },
  { model: 'Person', table: 'person', disposition: 'CASCADE', holds: 'the people: names, phones, emails, notes, relation, deceased flag' },
  { model: 'PersonTrait', table: 'person_trait', disposition: 'CASCADE', holds: 'hobbies, likes, dislikes, budget band' },
  { model: 'Event', table: 'event', disposition: 'CASCADE', holds: 'birthdays, wedding and death anniversaries' },
  {
    model: 'EventOccurrence',
    table: 'event_occurrence',
    disposition: 'TRANSITIVE',
    holds: 'the materialised dates. **No `user_id` at all** — reached only through `event`, which is why the spec asserts on it directly rather than trusting the pattern',
  },
  { model: 'Reminder', table: 'reminder', disposition: 'CASCADE', holds: 'schedules and delivery outcomes' },
  {
    model: 'Device',
    table: 'device',
    disposition: 'CASCADE',
    holds: '**FCM tokens.** The one whose failure is visible and awful: a token that outlives the account keeps pushing reminders about somebody’s late father to a phone whose owner deleted their account last week',
  },
  { model: 'RefreshToken', table: 'refresh_token', disposition: 'CASCADE', holds: 'session material (hashes, not tokens)' },
  { model: 'Wish', table: 'wish', disposition: 'CASCADE', holds: 'the text of messages sent to named people' },
  { model: 'Poster', table: 'poster', disposition: 'CASCADE', holds: 'which theme, which person, which sentiment' },
  {
    model: 'Asset',
    table: 'asset',
    disposition: 'CASCADE',
    holds: 'the rows that point at photographs. **The bytes are removed first, separately** — see `AccountDeletionService`',
  },
  {
    model: 'Generation',
    table: 'generation',
    disposition: 'ANONYMISE',
    holds: 'status, cache hit, latency, INR cost, moderation verdict. No prompt text, no name. Dipen’s decision: anonymise, so §13’s cache-hit metric does not develop holes',
  },
  {
    model: 'AiUsage',
    table: 'ai_usage',
    disposition: 'ANONYMISE',
    holds: 'provider, model, tokens, INR, pricing version. **No user content whatsoever.** §10 rule 2 and FR-POS-10 call these rows non-negotiable; DPDP says the row must not identify anybody. Nulling the id satisfies both',
  },
  {
    model: 'ContentReport',
    table: 'content_report',
    disposition: 'ANONYMISE',
    holds: 'a report the user made about generated content. Evidence about the CONTENT, not a record about the reporter',
  },
];

/**
 * Models that carry a `userId` and are deliberately NOT in the inventory.
 *
 * Empty today. It exists so that the DMMF guard has somewhere to put a deliberate exclusion
 * with a reason attached, instead of somebody weakening the guard itself.
 */
export const DELETION_EXEMPT: readonly { model: string; why: string }[] = [];

/**
 * `theme_cache` is the one table with media in it that is NOT here, and the omission is a
 * decision.
 *
 * It holds shared product artwork, keyed by `(theme, event type, milestone, palette, aspect
 * ratio)` and deliberately person-independent — that is FR-POS-03's entire economic thesis.
 * It has no `user_id`, so the DMMF guard does not see it either. `docs/M6_PLAN.md` §3.6 spelled
 * out what deleting it would cost: every subsequent poster becomes a live generation and the
 * bill goes up with nothing on screen having changed.
 */
export const DELETION_NEVER_TOUCHES = ['theme_cache'] as const;

/**
 * The Redis key prefixes that carry a user id.
 *
 * All four are fixed-window rate-limit counters with a TTL of at most a day, so they expire on
 * their own. They are deleted anyway: "it expires eventually" is not an answer to an erasure
 * request, and the point of the inventory is that nothing is left to time.
 *
 * Kept beside the rate limiters' own prefixes rather than re-derived — `draft-rate-limit.service.ts`,
 * `poster-rate-limit.service.ts` and `photo-rate-limit.service.ts` build keys as
 * `<prefix>:<h|d>:<userId>:<bucket>`.
 */
export const REDIS_USER_KEY_PREFIXES = ['wishdraft', 'poster', 'postercopy', 'photo'] as const;
