import { RemindersService } from './reminders.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { FirebaseService } from '../../firebase/firebase.service';
import type { PersonDerivationService } from '../people/person-derivation.service';

/**
 * The back door into FR-REM-11.
 *
 * `ladderFor` makes it impossible to CREATE a T-7 reminder for a solemn occurrence. That is
 * only half the guarantee, because `revivePending` turns SUPPRESSED rows back into PENDING
 * ones, and the rows it is designed to revive — an un-muted event, a restored person — are
 * exactly the rows a remembrance birthday has (FR-EVT-05).
 *
 * The sequence that found this on a real server:
 *
 *   1. a living person has a birthday with all three rungs PENDING;
 *   2. a death anniversary is added, so the person is marked deceased, every event becomes
 *      SOLEMN, and the birthday is muted by default — all three rungs suppressed;
 *   3. the user opts into the remembrance birthday and un-mutes it.
 *
 * At step 3 the birthday's D7 row is suppressed, un-expired and attached to a live unmuted
 * event, so an unfiltered revive brings it back — a "buy them a present, one week to go"
 * notification for someone who has died. REQUIREMENTS.md §11 files that under correctness.
 */

interface FakeRow {
  id: string;
  scheduledAtUtc: Date;
  offset: 'D7' | 'D1' | 'D0' | 'DIGEST';
  eventOccurrence: { event: { sentiment: 'CELEBRATORY' | 'SOLEMN' } } | null;
}

function buildService(rows: FakeRow[], ladder = { ladderD7: true, ladderD1: true, ladderD0: true }) {
  const revived: string[] = [];
  const prisma = {
    user: { findUnique: async () => ladder },
    reminder: {
      findMany: async () => rows,
      updateMany: async ({ where }: { where: { id: { in: string[] } } }) => {
        revived.push(...where.id.in);
        return { count: where.id.in.length };
      },
    },
  } as unknown as PrismaService;

  const service = new RemindersService(
    prisma,
    {} as FirebaseService,
    { recompute: async () => undefined } as unknown as PersonDerivationService,
  );
  return { service, revived };
}

const FUTURE = new Date('2027-06-08T03:30:00.000Z');
const NOW = new Date('2026-11-25T13:00:00.000Z');

function row(
  id: string,
  offset: FakeRow['offset'],
  sentiment: 'CELEBRATORY' | 'SOLEMN',
): FakeRow {
  return { id, scheduledAtUtc: FUTURE, offset, eventOccurrence: { event: { sentiment } } };
}

describe('revivePending and the solemn ladder (FR-REM-11)', () => {
  it('never revives D7 for a solemn occurrence', async () => {
    const { service, revived } = buildService([
      row('r7', 'D7', 'SOLEMN'),
      row('r1', 'D1', 'SOLEMN'),
      row('r0', 'D0', 'SOLEMN'),
    ]);

    const count = await service.revivePending('u1', NOW);

    expect(revived).toEqual(['r1', 'r0']);
    expect(count).toBe(2);
  });

  it('still revives all three rungs for a celebratory occurrence', async () => {
    const { service, revived } = buildService([
      row('r7', 'D7', 'CELEBRATORY'),
      row('r1', 'D1', 'CELEBRATORY'),
      row('r0', 'D0', 'CELEBRATORY'),
    ]);

    await service.revivePending('u1', NOW);

    expect(revived).toEqual(['r7', 'r1', 'r0']);
  });

  it('does not revive a rung the user has switched off', async () => {
    const { service, revived } = buildService(
      [row('r7', 'D7', 'CELEBRATORY'), row('r0', 'D0', 'CELEBRATORY')],
      { ladderD7: false, ladderD1: true, ladderD0: true },
    );

    await service.revivePending('u1', NOW);

    expect(revived).toEqual(['r0']);
  });

  /** A self-test has no event and therefore no ladder; it must not be filtered out. */
  it('leaves rows with no occurrence alone', async () => {
    const { service, revived } = buildService([
      { id: 'test', scheduledAtUtc: FUTURE, offset: 'D0', eventOccurrence: null },
    ]);

    await service.revivePending('u1', NOW);

    expect(revived).toEqual(['test']);
  });

  it('still refuses to revive anything whose moment has passed', async () => {
    const stale = row('old', 'D0', 'CELEBRATORY');
    stale.scheduledAtUtc = new Date('2026-11-01T03:30:00.000Z');
    const { service, revived } = buildService([stale]);

    expect(await service.revivePending('u1', NOW)).toBe(0);
    expect(revived).toEqual([]);
  });
});
