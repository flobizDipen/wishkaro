import type { EventType } from '@prisma/client';
import { buildPosterCopy } from './poster-copy';
import { BANNED_ON_SOLEMN } from './theme-library';

const ALL_TYPES: EventType[] = [
  'BIRTHDAY',
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
  'DEATH_ANNIVERSARY',
  'CUSTOM',
];

/**
 * REQUIREMENTS.md §11 files "no SOLEMN event ever produces celebratory copy" under
 * correctness, not content. So this is asserted over EVERY event type and every milestone
 * shape rather than over the one or two a reviewer would think of — the M5 bugs both
 * survived a full green suite because the combination that broke was the one nobody wrote.
 */
describe('buildPosterCopy — a solemn occasion can never reach celebratory words', () => {
  it('says nothing celebratory for any event type once the sentiment is SOLEMN', () => {
    for (const eventType of ALL_TYPES) {
      for (const milestone of [null, '12 years', '25th Silver']) {
        const copy = buildPosterCopy({
          personName: 'Asha Kulkarni',
          eventType,
          sentiment: 'SOLEMN',
          milestone,
        });

        const all = `${copy.headline} ${copy.subline ?? ''}`.toLowerCase();
        expect(all).not.toContain('happy');
        for (const banned of BANNED_ON_SOLEMN) {
          expect(all).not.toContain(banned);
        }
        expect(copy.headline).toBe('Remembering Asha Kulkarni');
      }
    }
  });

  it('passes the milestone through untouched rather than re-deriving it', () => {
    // `milestoneLabelFor()` has already refused to call a remembrance "25th Silver". This
    // layer must not add a second opinion — if it reformatted, the two would drift and the
    // cache key (which uses the label) would split.
    const copy = buildPosterCopy({
      personName: 'Asha',
      eventType: 'DEATH_ANNIVERSARY',
      sentiment: 'SOLEMN',
      milestone: '12 years',
    });
    expect(copy.subline).toBe('12 years');
  });
});

describe('buildPosterCopy — celebratory', () => {
  it('greets by name on a birthday and carries the milestone', () => {
    const copy = buildPosterCopy({
      personName: 'Rohan',
      eventType: 'BIRTHDAY',
      sentiment: 'CELEBRATORY',
      milestone: null,
    });
    expect(copy.headline).toBe('Happy Birthday, Rohan');
    expect(copy.subline).toBeNull();
  });

  it('invents no occasion wording for a CUSTOM event', () => {
    // The server does not know what the user's own label means, so it greets the person and
    // says nothing it cannot stand behind.
    const copy = buildPosterCopy({
      personName: 'Meera',
      eventType: 'CUSTOM',
      sentiment: 'CELEBRATORY',
      milestone: null,
    });
    expect(copy.headline).toBe('Meera');
    expect(copy.subline).toBeNull();
  });

  it('trims a name rather than rendering the user’s stray spaces at 72px', () => {
    const copy = buildPosterCopy({
      personName: '  Rohan  ',
      eventType: 'BIRTHDAY',
      sentiment: 'CELEBRATORY',
      milestone: null,
    });
    expect(copy.headline).toBe('Happy Birthday, Rohan');
  });
});
