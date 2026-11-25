import type { EventType } from '@prisma/client';
import { sentimentFor } from './event-rules';
import { buildReminderCopy } from '../reminders/reminder-copy';

const ALL_TYPES: EventType[] = [
  'BIRTHDAY',
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
  'DEATH_ANNIVERSARY',
  'CUSTOM',
];

describe('sentimentFor', () => {
  it('makes a death anniversary SOLEMN', () => {
    expect(sentimentFor('DEATH_ANNIVERSARY')).toBe('SOLEMN');
  });

  it.each(ALL_TYPES.filter((t) => t !== 'DEATH_ANNIVERSARY'))('makes %s CELEBRATORY', (type) => {
    expect(sentimentFor(type)).toBe('CELEBRATORY');
  });
});

/**
 * REQUIREMENTS.md §11 makes this a correctness requirement, not a content preference:
 * no SOLEMN event may ever produce celebratory copy. M0 cannot create a death anniversary,
 * but the guard is asserted now so M1 cannot quietly break it.
 */
describe('tone safety', () => {
  const CELEBRATORY_WORDS = /celebrat|congrat|happy|wish them|🎂|💍|🎉/i;

  it('never produces celebratory copy for a SOLEMN event', () => {
    for (const years of [null, 1, 12]) {
      const copy = buildReminderCopy({
        personName: 'Ravi',
        eventType: 'DEATH_ANNIVERSARY',
        sentiment: sentimentFor('DEATH_ANNIVERSARY'),
        years,
      });
      expect(`${copy.title} ${copy.body}`).not.toMatch(CELEBRATORY_WORDS);
    }
  });

  it('uses no emoji at all for a SOLEMN event', () => {
    const copy = buildReminderCopy({
      personName: 'Ravi',
      eventType: 'DEATH_ANNIVERSARY',
      sentiment: 'SOLEMN',
      years: 12,
    });
    expect(`${copy.title} ${copy.body}`).not.toMatch(/\p{Extended_Pictographic}/u);
  });

  it('never states an age when the year is unknown (FR-EVT-02)', () => {
    const copy = buildReminderCopy({
      personName: 'Meera',
      eventType: 'BIRTHDAY',
      sentiment: 'CELEBRATORY',
      years: null,
    });
    expect(`${copy.title} ${copy.body}`).not.toMatch(/\d/);
  });

  it('states the age when the year IS known', () => {
    const copy = buildReminderCopy({
      personName: 'Aarav',
      eventType: 'BIRTHDAY',
      sentiment: 'CELEBRATORY',
      years: 36,
    });
    expect(copy.body).toContain('36');
  });
});
