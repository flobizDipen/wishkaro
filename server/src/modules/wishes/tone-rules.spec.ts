import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { EventType, Sentiment, WishTone } from '@prisma/client';
import {
  ToneNotAllowedError,
  assertToneAllowed,
  defaultToneFor,
  isToneAllowed,
  sentimentFor,
  tonesFor,
} from './tone-rules';

/**
 * Half of the shared table. The other half is `WishToneRulesTest.kt`, reading THIS fixture.
 *
 * `docs/BUILD_PROMPT_M5.md`, definition of done item 9: "The solemn restriction must be red
 * if someone adds Funny back." That is the `expect: false` row for SOLEMN/FUNNY below, and it
 * asserts on the REJECTION rather than on a list comparison — so it tests the behaviour a
 * caller actually meets, not a restatement of the data.
 */

interface Fixture {
  tones: WishTone[];
  languages: string[];
  allowed: Record<Sentiment, WishTone[]>;
  default_tone: Record<Sentiment, WishTone>;
  cases: { sentiment: Sentiment; tone: WishTone; expect: boolean; why: string }[];
  sentiment_cases: {
    eventType: EventType;
    isDeceased: boolean;
    sentiment: Sentiment;
    why: string;
  }[];
}

const fixture: Fixture = JSON.parse(
  readFileSync(join(__dirname, '../../../../docs/fixtures/wish-tones.json'), 'utf8'),
) as Fixture;

describe('wish tone rules — the shared table of cases', () => {
  it('has a table to read', () => {
    // A path typo would otherwise make this whole suite silently vacuous, which is the
    // failure mode a fixture-driven suite has and an inline one does not.
    expect(fixture.cases.length).toBeGreaterThan(10);
    expect(fixture.sentiment_cases.length).toBeGreaterThan(8);
  });

  it('covers the whole cross-product of sentiments and tones', () => {
    // Without this, adding a seventh tone would leave it untested on both sentiments and the
    // suite would stay green. `ToneTest.kt` guards the static copy the same way.
    const covered = new Set(fixture.cases.map((c) => `${c.sentiment}/${c.tone}`));
    for (const sentiment of ['CELEBRATORY', 'SOLEMN'] as Sentiment[]) {
      for (const tone of fixture.tones) {
        expect(covered.has(`${sentiment}/${tone}`)).toBe(true);
      }
    }
  });

  describe.each(fixture.cases)(
    '$sentiment + $tone',
    ({ sentiment, tone, expect: allowed, why }) => {
      it(`is ${allowed ? 'allowed' : 'REFUSED'} — ${why}`, () => {
        expect(isToneAllowed(sentiment, tone)).toBe(allowed);
      });

      it(`${allowed ? 'passes' : 'throws on'} assertToneAllowed`, () => {
        if (allowed) {
          expect(() => assertToneAllowed(sentiment, tone)).not.toThrow();
        } else {
          // Rejected, not coerced. A request for FUNNY on a death anniversary must be a 400
          // naming the tone, so that a client carrying that bug finds out.
          expect(() => assertToneAllowed(sentiment, tone)).toThrow(ToneNotAllowedError);
        }
      });
    },
  );

  it('agrees with the fixture about the whole allowed list, not just case by case', () => {
    // Case-by-case checks cannot catch an EXTRA tone that the fixture never mentions —
    // tonesFor() returning something the table does not list would pass every row above.
    for (const sentiment of ['CELEBRATORY', 'SOLEMN'] as Sentiment[]) {
      expect([...tonesFor(sentiment)].sort()).toEqual([...fixture.allowed[sentiment]].sort());
    }
  });

  it('defaults to a tone that is itself allowed', () => {
    for (const sentiment of ['CELEBRATORY', 'SOLEMN'] as Sentiment[]) {
      expect(defaultToneFor(sentiment)).toBe(fixture.default_tone[sentiment]);
      expect(isToneAllowed(sentiment, defaultToneFor(sentiment))).toBe(true);
    }
  });

  it('never offers FUNNY or POETIC on a solemn occasion', () => {
    // Said twice on purpose. The table above is data and could be edited; this is the
    // requirement in one line, where a reviewer reading the diff will see it.
    expect(tonesFor('SOLEMN')).not.toContain('FUNNY');
    expect(tonesFor('SOLEMN')).not.toContain('POETIC');
  });
});

describe('sentiment comes from the person, never from the event type', () => {
  describe.each(fixture.sentiment_cases)(
    '$eventType, deceased=$isDeceased',
    ({ eventType, isDeceased, sentiment, why }) => {
      it(`is ${sentiment}${why ? ` — ${why}` : ''}`, () => {
        expect(sentimentFor(eventType, isDeceased)).toBe(sentiment);
      });
    },
  );

  it('offers no celebratory tone on a deceased person’s BIRTHDAY', () => {
    // The whole chain in one assertion, because the chain is where M1 broke: the type says
    // BIRTHDAY, the person is deceased, so the sentiment is SOLEMN, so the tone list is the
    // solemn one. A composer that skipped the middle step would offer FUNNY here.
    const sentiment = sentimentFor('BIRTHDAY', true);
    expect(sentiment).toBe('SOLEMN');
    expect(tonesFor(sentiment)).toEqual(['RESPECTFUL', 'SHORT', 'HEARTFELT']);
  });
});
