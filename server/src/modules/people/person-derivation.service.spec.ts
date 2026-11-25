import type { EventType, Sentiment, TraitKind } from '@prisma/client';
import { PersonDerivationService } from './person-derivation.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { AppEnv } from '../../config/env';

/**
 * The stateful half of the derivation rules.
 *
 * `derived.ts` is tested exhaustively, but it is pure — it answers "what should this value
 * be", never "did this person just change". The transition is the part that carries product
 * risk, and it was the part with no test: ticking the deceased box on a profile left the
 * person's birthday unmuted, because `recompute` re-reads the row AFTER the caller has
 * already written `is_deceased`, so `becameDeceased` was `!true && true`.
 *
 * Sentiment was still correct on that path, so nothing said "Happy Birthday" — but a
 * remembrance reminder the user never opted into would have fired. FR-EVT-05 says the
 * opt-in is opt-IN.
 *
 * Driven against a hand-built fake rather than a database: the branch logic is what carries
 * the risk, and a test that needs Postgres is a test nobody runs before pushing.
 */

interface FakeEvent {
  id: string;
  type: EventType;
  sentiment: Sentiment;
  isMuted: boolean;
  day: number;
  month: number;
  year: number | null;
  deletedAt: Date | null;
}

interface FakePerson {
  id: string;
  isDeceased: boolean;
  isMinor: boolean;
  completenessScore: number;
  relationToOwner: string | null;
  gender: string | null;
  phone: string | null;
  email: string | null;
  notes: string | null;
}

interface World {
  service: PersonDerivationService;
  person: FakePerson;
  events: FakeEvent[];
  event(id: string): FakeEvent;
}

function event(id: string, type: EventType, overrides: Partial<FakeEvent> = {}): FakeEvent {
  return {
    id,
    type,
    // Deliberately seeded CELEBRATORY so a test that expects SOLEMN is proving the
    // re-derivation ran, not that the fixture already said the right thing.
    sentiment: 'CELEBRATORY',
    isMuted: false,
    day: 22,
    month: 9,
    year: 1988,
    deletedAt: null,
    ...overrides,
  };
}

function buildWorld(
  personOverrides: Partial<FakePerson>,
  events: FakeEvent[],
  traits: { kind: TraitKind }[] = [],
): World {
  const person: FakePerson = {
    id: 'p1',
    isDeceased: false,
    isMinor: false,
    completenessScore: 0,
    relationToOwner: 'Uncle',
    gender: null,
    phone: null,
    email: null,
    notes: null,
    ...personOverrides,
  };

  const prisma = {
    person: {
      findUnique: async () => ({
        ...person,
        user: { timezone: 'Asia/Kolkata' },
        events: events.filter((e) => e.deletedAt === null),
        traits,
      }),
      update: async ({ data }: { data: Partial<FakePerson> }) => {
        Object.assign(person, data);
        return person;
      },
    },
    event: {
      update: async ({ where, data }: { where: { id: string }; data: Partial<FakeEvent> }) => {
        const row = events.find((e) => e.id === where.id);
        if (row) Object.assign(row, data);
        return row;
      },
    },
  } as unknown as PrismaService;

  const env = { DEFAULT_TIMEZONE: 'Asia/Kolkata' } as AppEnv;

  return {
    service: new PersonDerivationService(prisma, env),
    person,
    events,
    event(id) {
      const found = events.find((e) => e.id === id);
      if (!found) throw new Error(`no such event in the fixture: ${id}`);
      return found;
    },
  };
}

const NOW = new Date('2026-11-25T06:00:00.000Z');

describe('PersonDerivationService.recompute — the deceased transition (FR-EVT-05)', () => {
  /**
   * (a) The path that was broken. `PeopleService.update` writes `is_deceased: true` and then
   * recomputes, so the row already reads true by the time the derivation looks at it. The
   * caller hands back what it overwrote; without that this test fails on the mute.
   */
  it('mutes an existing birthday when the profile flag is ticked', async () => {
    const world = buildWorld({ isDeceased: true }, [
      event('birthday', 'BIRTHDAY'),
      event('wedding', 'WEDDING_ANNIVERSARY'),
    ]);

    await world.service.recompute('p1', undefined, NOW, { wasDeceased: false });

    expect(world.event('birthday').isMuted).toBe(true);
    expect(world.event('birthday').sentiment).toBe('SOLEMN');
    expect(world.event('wedding').sentiment).toBe('SOLEMN');
    // A wedding anniversary is not silenced — only the birthday is opt-in (FR-EVT-05).
    expect(world.event('wedding').isMuted).toBe(false);
  });

  /**
   * The regression itself, pinned. This is what the code did before the fix, and what it
   * still does if a future caller writes the flag and forgets to pass the context — which
   * is why `wasDeceased` exists rather than a comparison inside the service.
   */
  it('cannot see the transition if the caller writes the flag and passes no context', async () => {
    const world = buildWorld({ isDeceased: true }, [event('birthday', 'BIRTHDAY')]);

    await world.service.recompute('p1', undefined, NOW);

    expect(world.event('birthday').isMuted).toBe(false);
    // Sentiment is still corrected, which is why the bug was invisible: the copy was solemn,
    // but a reminder nobody asked for would still have been scheduled.
    expect(world.event('birthday').sentiment).toBe('SOLEMN');
  });

  /**
   * (b) The path that always worked, kept so the two stay in step. Nothing has written
   * `is_deceased` here — the death anniversary is what makes it true — so the database is
   * the authority on the previous value and no context is needed.
   */
  it('mutes an existing birthday when a death anniversary is created', async () => {
    const world = buildWorld({ isDeceased: false }, [
      event('birthday', 'BIRTHDAY'),
      event('death', 'DEATH_ANNIVERSARY', { sentiment: 'SOLEMN', day: 3, month: 4, year: 2020 }),
    ]);

    await world.service.recompute('p1', undefined, NOW);

    expect(world.person.isDeceased).toBe(true);
    expect(world.event('birthday').isMuted).toBe(true);
    expect(world.event('birthday').sentiment).toBe('SOLEMN');
  });

  /**
   * (c) The reason the mute is on the transition and not on every pass. Un-muting IS the
   * opt-in; a later write that touches this person must not take it back.
   */
  it('never re-mutes a remembrance birthday the user opted into', async () => {
    const world = buildWorld({ isDeceased: true }, [
      event('birthday', 'BIRTHDAY', { sentiment: 'SOLEMN', isMuted: false }),
      event('death', 'DEATH_ANNIVERSARY', { sentiment: 'SOLEMN', day: 3, month: 4, year: 2020 }),
    ]);

    // A later edit — adding a note, say — recomputes with the flag unchanged on both sides.
    await world.service.recompute('p1', undefined, NOW, { wasDeceased: true });

    expect(world.event('birthday').isMuted).toBe(false);
  });

  it('leaves a living person alone', async () => {
    const world = buildWorld({ isDeceased: false }, [event('birthday', 'BIRTHDAY')]);

    await world.service.recompute('p1', undefined, NOW, { wasDeceased: false });

    expect(world.person.isDeceased).toBe(false);
    expect(world.event('birthday').isMuted).toBe(false);
    expect(world.event('birthday').sentiment).toBe('CELEBRATORY');
  });

  /**
   * Deleting a death anniversary must not quietly return someone to celebratory copy. The
   * flag is an OR, not an assignment, and the people service refuses the explicit un-tick
   * while the event is still live.
   */
  it('does not clear the flag when the death anniversary is gone', async () => {
    const world = buildWorld({ isDeceased: true }, [
      event('birthday', 'BIRTHDAY', { sentiment: 'SOLEMN', isMuted: true }),
    ]);

    await world.service.recompute('p1', undefined, NOW, { wasDeceased: true });

    expect(world.person.isDeceased).toBe(true);
    expect(world.event('birthday').sentiment).toBe('SOLEMN');
  });
});

describe('PersonDerivationService.recompute — the values that change on their own', () => {
  it('clears is_minor once the person has turned eighteen', async () => {
    // Born 2008-09-22; on 2026-11-25 they are 18. Nothing was written to make this true.
    const world = buildWorld({ isMinor: true }, [
      event('birthday', 'BIRTHDAY', { day: 22, month: 9, year: 2008 }),
    ]);

    await world.service.recompute('p1', undefined, NOW);

    expect(world.person.isMinor).toBe(false);
  });

  it('backfills a completeness score that was never computed', async () => {
    const world = buildWorld(
      { completenessScore: 0, relationToOwner: 'Uncle', phone: '+919876543210' },
      [event('birthday', 'BIRTHDAY')],
    );

    await world.service.recompute('p1', undefined, NOW);

    // relation 15 + birthYear 10 + phone 5.
    expect(world.person.completenessScore).toBe(30);
  });

  /**
   * A child born in 2015. The flag goes ON here with no write, exactly as it goes off at
   * eighteen. The score still counts what is already known — flagging a minor suppresses
   * the *enrichment* (traits are refused, and the suggestion list is withheld), not the
   * arithmetic over fields that already exist.
   */
  it('flags a minor from the birth year alone', async () => {
    const world = buildWorld({ isMinor: false, completenessScore: 0 }, [
      event('birthday', 'BIRTHDAY', { day: 22, month: 9, year: 2015 }),
    ]);

    await world.service.recompute('p1', undefined, NOW);

    expect(world.person.isMinor).toBe(true);
    // relation 15 + birthYear 10, and nothing from traits.
    expect(world.person.completenessScore).toBe(25);
  });
});

describe('PersonDerivationService.recomputeMany', () => {
  it('passes each person their own pre-write state', async () => {
    const seen: { id: string; wasDeceased: boolean | undefined }[] = [];
    const service = Object.create(PersonDerivationService.prototype) as PersonDerivationService;
    (service as unknown as { recompute: unknown }).recompute = async (
      id: string,
      _db: unknown,
      _now: Date,
      context: { wasDeceased?: boolean },
    ) => {
      seen.push({ id, wasDeceased: context.wasDeceased });
    };

    await service.recomputeMany(
      ['p1', 'p2', 'p3'],
      undefined,
      new Map([
        ['p1', { wasDeceased: false }],
        ['p2', { wasDeceased: true }],
      ]),
    );

    expect(seen).toEqual([
      { id: 'p1', wasDeceased: false },
      { id: 'p2', wasDeceased: true },
      // p3 was not written by this batch, so the database is the authority on its past.
      { id: 'p3', wasDeceased: undefined },
    ]);
  });
});
