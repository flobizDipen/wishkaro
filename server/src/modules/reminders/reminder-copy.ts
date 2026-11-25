import type { EventType, Sentiment } from '@prisma/client';

export interface ReminderCopyInput {
  personName: string;
  eventType: EventType;
  sentiment: Sentiment;
  /** Years elapsed, or null when the year is unknown (FR-EVT-02). */
  years: number | null;
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

  if (sentiment === 'SOLEMN') {
    return {
      title: `Remembering ${personName}`,
      body: years === null ? 'Today is their remembrance day.' : `${years} years today.`,
    };
  }

  switch (input.eventType) {
    case 'BIRTHDAY':
      return {
        title: `🎂 ${personName}'s birthday is today`,
        body:
          years === null
            ? 'Send them a wish before the day gets away from you.'
            : `They turn ${years} today. Send them a wish.`,
      };
    case 'WEDDING_ANNIVERSARY':
      return {
        title: `💍 ${personName}'s wedding anniversary is today`,
        body: years === null ? 'Send them a wish.' : `${years} years today. Send them a wish.`,
      };
    case 'ENGAGEMENT_ANNIVERSARY':
      return {
        title: `${personName}'s engagement anniversary is today`,
        body: years === null ? 'Send them a wish.' : `${years} years today. Send them a wish.`,
      };
    default:
      return {
        title: `${personName} — today`,
        body: 'You asked to be reminded about this.',
      };
  }
}

export function buildTestCopy(): ReminderCopy {
  return {
    title: 'WishKaro test reminder',
    body: 'If you can read this, push delivery works on this device.',
  };
}
