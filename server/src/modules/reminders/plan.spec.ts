import { RemindersService } from './reminders.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { FirebaseService } from '../../firebase/firebase.service';
import type { PersonDerivationService } from '../people/person-derivation.service';

/**
 * `GET /v1/reminders/plan` — the wiring the phone's alarms are armed from.
 *
 * Worth testing as composition rather than in pieces, for the same reason as
 * `materialise.spec.ts`: the copy layer, the ladder and the digest grouping are each proved
 * elsewhere, and the thing that assembles them is where M1's reviewed bugs lived.
 *
 * The case that earns this file is the last one. Digest mode suppresses every rung, so the
 * day's contents are read by SCHEDULE rather than by status — which makes a rung the ladder
 * no longer wants indistinguishable from one it does. A stale D7 row on an event that became
 * SOLEMN after it was created would otherwise reappear as an "In 7 days" line: the T-7
 * gifting nudge FR-REM-11 forbids, smuggled in as a list item.
 */

const USER = {
  id: 'u1',
  timezone: 'Asia/Kolkata',
  reminderHour: 9,
  ladderD7: true,
  ladderD1: true,
  ladderD0: true,
  quietHoursStart: null,
  quietHoursEnd: null,
  digestEnabled: false,
  digestHour: 9,
  devices: [],
};

interface Row {
  id: string;
  dedupeKey: string;
  offset: string;
  scheduledAtUtc: Date;
  status: string;
  person: { displayName: string };
  sentiment: 'CELEBRATORY' | 'SOLEMN';
  type?: string;
  occurrenceYear?: number;
}

function row(partial: Partial<Row> & Pick<Row, 'dedupeKey' | 'offset' | 'scheduledAtUtc'>): Row {
  return {
    id: `id-${partial.dedupeKey}`,
    status: 'PENDING',
    person: { displayName: 'Ravi' },
    sentiment: 'CELEBRATORY',
    type: 'BIRTHDAY',
    occurrenceYear: 2026,
    ...partial,
  };
}

function build(rows: Row[], user: Partial<typeof USER> = {}) {
  const merged = { ...USER, ...user };

  const shape = (r: Row) => ({
    id: r.id,
    dedupeKey: r.dedupeKey,
    offset: r.offset,
    scheduledAtUtc: r.scheduledAtUtc,
    status: r.status,
    user: merged,
    eventOccurrence:
      r.offset === 'DIGEST'
        ? null
        : {
            id: `occ-${r.id}`,
            occurrenceYear: r.occurrenceYear ?? 2026,
            event: {
              id: `event-${r.id}`,
              personId: 'p1',
              type: r.type ?? 'BIRTHDAY',
              sentiment: r.sentiment,
              day: 15,
              month: 6,
              year: 1990,
              person: r.person,
            },
          },
  });

  let call = 0;
  const prisma = {
    reminder: {
      findMany: async () => {
        call += 1;
        // First call: the PENDING rows to arm. Second: the day's rungs for any digest.
        return call === 1
          ? rows.filter((r) => r.status === 'PENDING').map(shape)
          : rows.filter((r) => r.offset !== 'DIGEST').map(shape);
      },
    },
  } as unknown as PrismaService;

  return new RemindersService(
    prisma,
    {} as FirebaseService,
    { recompute: async () => undefined } as unknown as PersonDerivationService,
  );
}

const NOW = new Date('2026-11-25T06:00:00.000Z');
const at = (iso: string) => new Date(iso);

describe('plan — what the phone is handed', () => {
  it('renders each rung with the copy the push would have carried', async () => {
    const service = build([
      row({ dedupeKey: 'e1:2026:D7', offset: 'D7', scheduledAtUtc: at('2026-12-03T03:30:00Z') }),
      row({ dedupeKey: 'e1:2026:D0', offset: 'D0', scheduledAtUtc: at('2026-12-10T03:30:00Z') }),
    ]);

    const plan = await service.plan('u1', 60, NOW);

    expect(plan.reminders.map((r) => r.title)).toEqual([
      "🎁 Ravi's birthday is in a week",
      "🎂 Ravi's birthday is today",
    ]);
    // The instant comes back on every entry — it is what the alarm is set to.
    expect(plan.reminders[0]?.scheduledAtUtc).toBe('2026-12-03T03:30:00.000Z');
    expect(plan.reminders[0]?.offset).toBe('D7');
  });

  it('keeps solemn copy out of the celebratory register', async () => {
    const service = build([
      row({
        dedupeKey: 'e2:2026:D0',
        offset: 'D0',
        scheduledAtUtc: at('2026-12-15T03:30:00Z'),
        sentiment: 'SOLEMN',
        type: 'DEATH_ANNIVERSARY',
        person: { displayName: 'Aai' },
      }),
    ]);

    const plan = await service.plan('u1', 60, NOW);

    expect(plan.reminders[0]?.title).toBe('Remembering Aai');
    expect(plan.reminders[0]?.sentiment).toBe('SOLEMN');
    expect(/\p{Extended_Pictographic}/u.test(plan.reminders[0]!.title)).toBe(false);
  });

  it('omits anything that is not PENDING', async () => {
    const service = build([
      row({
        dedupeKey: 'e1:2026:D7',
        offset: 'D7',
        scheduledAtUtc: at('2026-12-03T03:30:00Z'),
        status: 'SUPPRESSED',
      }),
      row({ dedupeKey: 'e1:2026:D0', offset: 'D0', scheduledAtUtc: at('2026-12-10T03:30:00Z') }),
    ]);

    const plan = await service.plan('u1', 60, NOW);

    expect(plan.reminders).toHaveLength(1);
    expect(plan.reminders[0]?.dedupeKey).toBe('e1:2026:D0');
  });
});

describe('plan — the digest reads the day without reading a banned rung', () => {
  const digestDay = at('2026-12-10T03:30:00Z');

  it('groups the day by rung', async () => {
    const service = build(
      [
        row({ dedupeKey: 'digest:u1:2026-12-10', offset: 'DIGEST', scheduledAtUtc: digestDay }),
        row({
          dedupeKey: 'e1:2026:D0',
          offset: 'D0',
          scheduledAtUtc: digestDay,
          status: 'SUPPRESSED',
        }),
        row({
          dedupeKey: 'e2:2026:D1',
          offset: 'D1',
          scheduledAtUtc: digestDay,
          status: 'SUPPRESSED',
          person: { displayName: 'Sita' },
        }),
      ],
      { digestEnabled: true },
    );

    const plan = await service.plan('u1', 60, NOW);

    expect(plan.reminders).toHaveLength(1);
    expect(plan.reminders[0]?.body.split('\n')).toEqual([
      'Today: Ravi (birthday)',
      'Tomorrow: Sita (birthday)',
    ]);
  });

  /**
   * The back door. A birthday created while the person was alive has a D7 row; the person is
   * later marked deceased, so the event becomes SOLEMN and the ladder stops wanting D7 — but
   * the row is still there, still on the right day, and still indistinguishable by status
   * from the rungs the digest is supposed to list.
   */
  it('never lists a T-7 line for a solemn occurrence', async () => {
    const service = build(
      [
        row({ dedupeKey: 'digest:u1:2026-12-10', offset: 'DIGEST', scheduledAtUtc: digestDay }),
        row({
          dedupeKey: 'e3:2026:D7',
          offset: 'D7',
          scheduledAtUtc: digestDay,
          status: 'SUPPRESSED',
          sentiment: 'SOLEMN',
          person: { displayName: 'Aai' },
        }),
      ],
      { digestEnabled: true },
    );

    const plan = await service.plan('u1', 60, NOW);

    expect(plan.reminders[0]?.body).not.toContain('In 7 days');
    expect(plan.reminders[0]?.body).not.toContain('Aai');
    expect(plan.reminders[0]?.itemCount).toBe('0');
  });

  it('also drops a rung the user has switched off', async () => {
    const service = build(
      [
        row({ dedupeKey: 'digest:u1:2026-12-10', offset: 'DIGEST', scheduledAtUtc: digestDay }),
        row({
          dedupeKey: 'e1:2026:D7',
          offset: 'D7',
          scheduledAtUtc: digestDay,
          status: 'SUPPRESSED',
        }),
      ],
      { digestEnabled: true, ladderD7: false },
    );

    const plan = await service.plan('u1', 60, NOW);

    expect(plan.reminders[0]?.itemCount).toBe('0');
  });

  /** A rung on a different day must not leak into this digest. */
  it('only reads its own local day', async () => {
    const service = build(
      [
        row({ dedupeKey: 'digest:u1:2026-12-10', offset: 'DIGEST', scheduledAtUtc: digestDay }),
        row({
          dedupeKey: 'e1:2026:D0',
          offset: 'D0',
          scheduledAtUtc: at('2026-12-11T03:30:00Z'),
          status: 'SUPPRESSED',
        }),
      ],
      { digestEnabled: true },
    );

    const plan = await service.plan('u1', 60, NOW);

    expect(plan.reminders[0]?.itemCount).toBe('0');
  });
});
