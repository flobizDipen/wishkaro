import type { EventType, ReminderOffset, Sentiment } from '@prisma/client';

export interface ReminderCopyInput {
  personName: string;
  eventType: EventType;
  sentiment: Sentiment;
  /** Years elapsed, or null when the year is unknown (FR-EVT-02). */
  years: number | null;
  /**
   * Which rung of the ladder this is (FR-REM-03). Defaults to morning-of so that every M0
   * and M1 call site keeps the copy it had.
   *
   * It changes the tense, not the register: "is today" becomes "is tomorrow" and "is in a
   * week". The SOLEMN branch below is chosen BEFORE this is consulted, so no rung can route
   * a solemn event into celebratory wording.
   */
  offset?: ReminderOffset;
}

export interface ReminderCopy {
  title: string;
  body: string;
}

/**
 * Notification copy, branched on `sentiment`.
 *
 * FR-EVT-01a calls this flag load-bearing rather than cosmetic, and REQUIREMENTS.md §11 makes
 * "no SOLEMN event ever produces celebratory copy" a correctness requirement. M0 only ever
 * writes CELEBRATORY birthdays, but the branch exists from the first line of code so that
 * adding DEATH_ANNIVERSARY in M1 cannot accidentally wish someone a happy anything.
 */
export function buildReminderCopy(input: ReminderCopyInput): ReminderCopy {
  const { personName, years, sentiment } = input;
  const offset = input.offset ?? 'D0';

  // The branch is taken BEFORE anything else is consulted, so no rung, type or milestone
  // can route a solemn event into the celebratory string set below.
  if (sentiment === 'SOLEMN') {
    if (offset === 'D1') {
      return {
        title: `Remembering ${personName} tomorrow`,
        body:
          years === null
            ? 'Their remembrance day is tomorrow.'
            : `It will be ${yearsPhrase(years)} tomorrow.`,
      };
    }
    // D7 is unreachable for a SOLEMN event — ladderFor() never creates the rung (FR-REM-11)
    // — but the copy layer does not rely on that to stay safe. If one ever arrives it gets
    // the same quiet register as the others, never the gifting one.
    if (offset === 'D7') {
      return {
        title: `Remembering ${personName} next week`,
        body:
          years === null
            ? 'Their remembrance day is in a week.'
            : `It will be ${yearsPhrase(years)} in a week.`,
      };
    }
    return {
      title: `Remembering ${personName}`,
      body: years === null ? 'Today is their remembrance day.' : `${years} years today.`,
    };
  }

  const when = WHEN[offset] ?? WHEN.D0;

  switch (input.eventType) {
    case 'BIRTHDAY':
      return {
        title: `${offset === 'D7' ? '🎁' : '🎂'} ${personName}'s birthday is ${when.phrase}`,
        body:
          years === null
            ? when.celebratoryNudge
            : `They turn ${years} ${when.phrase}. ${when.celebratoryNudge}`,
      };
    case 'WEDDING_ANNIVERSARY':
      return {
        title: `${offset === 'D7' ? '🎁' : '💍'} ${personName}'s wedding anniversary is ${when.phrase}`,
        body:
          years === null
            ? when.celebratoryNudge
            : `${years} years ${when.phrase}. ${when.celebratoryNudge}`,
      };
    case 'ENGAGEMENT_ANNIVERSARY':
      return {
        title: `${personName}'s engagement anniversary is ${when.phrase}`,
        body:
          years === null
            ? when.celebratoryNudge
            : `${years} years ${when.phrase}. ${when.celebratoryNudge}`,
      };
    default:
      return {
        title: `${personName} — ${when.phrase}`,
        body: 'You asked to be reminded about this.',
      };
  }
}

/**
 * The tense each rung speaks in, and the action it nudges towards.
 *
 * T-7 is framed around gifting, which is the entire reason FR-REM-11 removes it from solemn
 * events — there is no present to buy for a death anniversary. Keeping that framing in one
 * table makes the exclusion legible rather than folklore.
 */
const WHEN: Record<string, { phrase: string; celebratoryNudge: string }> = {
  D7: { phrase: 'in a week', celebratoryNudge: 'Time to think about a gift.' },
  D1: { phrase: 'tomorrow', celebratoryNudge: 'Get ready to send them a wish.' },
  D0: { phrase: 'today', celebratoryNudge: 'Send them a wish.' },
};

/** "1 year" / "12 years" — never an ordinal, which reads as an achievement (FR-EVT-03). */
function yearsPhrase(years: number): string {
  return years === 1 ? '1 year' : `${years} years`;
}

export interface DigestItem {
  personName: string;
  eventType: EventType;
  sentiment: Sentiment;
  offset: ReminderOffset;
}

/**
 * One notification standing in for the whole day's ladder (FR-REM-08).
 *
 * Deliberately plain. A digest is a list, and a list that has to be safe for a death
 * anniversary and a birthday on the same line has no room for a register at all — so it has
 * none, and the tone-safety test covers it alongside the per-event copy. The only thing the
 * sentiment changes here is the leading glyph, which is absent for SOLEMN rather than
 * replaced.
 */
export function buildDigestCopy(items: readonly DigestItem[]): ReminderCopy {
  const today = items.filter((i) => i.offset === 'D0');
  const tomorrow = items.filter((i) => i.offset === 'D1');
  const nextWeek = items.filter((i) => i.offset === 'D7');

  const sections: string[] = [];
  if (today.length > 0) sections.push(`Today: ${names(today)}`);
  if (tomorrow.length > 0) sections.push(`Tomorrow: ${names(tomorrow)}`);
  if (nextWeek.length > 0) sections.push(`In 7 days: ${names(nextWeek)}`);

  const count = items.length;
  // No product name in the title. Android already prints it above every notification, and
  // "WishKaro" contains the word this product uses for the celebratory action — which is a
  // false positive the tone-safety scan should not have to be taught to ignore on a
  // notification that may be listing a death anniversary.
  return {
    title: count === 1 ? '1 date coming up' : `${count} dates coming up`,
    body: sections.join('\n'),
  };
}

function names(items: readonly DigestItem[]): string {
  return items.map((i) => `${i.personName} (${EVENT_NOUN[i.eventType]})`).join(', ');
}

/**
 * Neutral nouns, usable on a line that may sit directly above a death anniversary. A
 * remembrance line and a birthday line share a notification here, so neither gets an
 * adjective.
 */
const EVENT_NOUN: Record<EventType, string> = {
  BIRTHDAY: 'birthday',
  WEDDING_ANNIVERSARY: 'wedding anniversary',
  ENGAGEMENT_ANNIVERSARY: 'engagement anniversary',
  DEATH_ANNIVERSARY: 'remembrance',
  CUSTOM: 'date',
};

export function buildTestCopy(): ReminderCopy {
  return {
    title: 'WishKaro test reminder',
    body: 'If you can read this, push delivery works on this device.',
  };
}
