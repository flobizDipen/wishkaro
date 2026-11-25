import { RemindersService } from './reminders.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { FirebaseService } from '../../firebase/firebase.service';
import type { PersonDerivationService } from '../people/person-derivation.service';

/**
 * FR-REM-10: "Delivery outcome is reported back to the server for the G1 metric."
 *
 * G1 is "reminder delivery > 98% on the OEM test matrix" (REQUIREMENTS.md §11), which is the
 * central claim this product makes. These tests are about whether the number it produces can
 * be trusted — in particular that a successful de-duplication is not recorded as a delivery
 * failure, and that a delivery the server never scheduled is still counted.
 */

interface FakeRow {
  id: string;
  userId: string;
  dedupeKey: string;
  status: string;
  channel: string;
  deliveryOutcome: string | null;
  deliveredAt: Date | null;
}

function buildService(rows: FakeRow[]): RemindersService {
  const prisma = {
    reminder: {
      findFirst: async ({ where }: { where: { dedupeKey: string; userId: string } }) =>
        rows.find((r) => r.dedupeKey === where.dedupeKey && r.userId === where.userId) ?? null,
      update: async ({ where, data }: { where: { id: string }; data: Record<string, unknown> }) => {
        const row = rows.find((r) => r.id === where.id);
        if (!row) throw new Error(`no row ${where.id}`);
        if (typeof data.deliveryOutcome === 'string') row.deliveryOutcome = data.deliveryOutcome;
        if (data.deliveredAt instanceof Date) row.deliveredAt = data.deliveredAt;
        if (typeof data.channel === 'string') row.channel = data.channel;
        if (typeof data.status === 'string') row.status = data.status;
        return row;
      },
    },
  } as unknown as PrismaService;

  return new RemindersService(
    prisma,
    {} as FirebaseService,
    { recompute: async () => undefined } as unknown as PersonDerivationService,
  );
}

function row(dedupeKey: string, userId = 'u1'): FakeRow {
  return {
    id: `id-${dedupeKey}`,
    userId,
    dedupeKey,
    status: 'SENT',
    channel: 'FCM',
    deliveryOutcome: null,
    deliveredAt: null,
  };
}

const AT = '2026-12-02T03:30:00.000Z';

describe('recordDelivery (FR-REM-10)', () => {
  it('marks a shown reminder DELIVERED, with the time the DEVICE acted', async () => {
    const rows = [row('e1:2026:D0')];
    const service = buildService(rows);

    const result = await service.recordDelivery('u1', [
      { dedupeKey: 'e1:2026:D0', outcome: 'SHOWN', channel: 'FCM', occurredAtUtc: AT },
    ]);

    expect(result).toEqual({ recorded: 1, unknown: 0 });
    expect(rows[0].status).toBe('DELIVERED');
    expect(rows[0].deliveryOutcome).toBe('SHOWN');
    // Not "when the report reached us" — a report can arrive days late, after an outage.
    expect(rows[0].deliveredAt?.toISOString()).toBe(AT);
  });

  /**
   * The point of the whole design. The reminder was scheduled as an FCM push; the phone's own
   * alarm got there first. `channel` has to record who WON, or the one query that answers
   * "how often does FCM actually fail?" answers "never", because every row says FCM.
   */
  it('flips channel to LOCAL when the local alarm won the race', async () => {
    const rows = [row('e1:2026:D0')];
    const service = buildService(rows);

    await service.recordDelivery('u1', [
      { dedupeKey: 'e1:2026:D0', outcome: 'SHOWN', channel: 'LOCAL', occurredAtUtc: AT },
    ]);

    expect(rows[0].channel).toBe('LOCAL');
    expect(rows[0].status).toBe('DELIVERED');
  });

  /**
   * Both paths report. The second one to arrive says SUPPRESSED_DUPLICATE, and that is the
   * system working — the user saw exactly one notification. If it overwrote SHOWN, every
   * successful de-duplication would erase the delivery it de-duplicated and the G1 number
   * would fall as reliability rose.
   */
  it('does not let a later SUPPRESSED_DUPLICATE undo a delivery', async () => {
    const rows = [row('e1:2026:D0')];
    const service = buildService(rows);

    await service.recordDelivery('u1', [
      { dedupeKey: 'e1:2026:D0', outcome: 'SHOWN', channel: 'LOCAL', occurredAtUtc: AT },
      {
        dedupeKey: 'e1:2026:D0',
        outcome: 'SUPPRESSED_DUPLICATE',
        channel: 'FCM',
        occurredAtUtc: '2026-12-02T04:00:00.000Z',
      },
    ]);

    expect(rows[0].deliveryOutcome).toBe('SHOWN');
    expect(rows[0].status).toBe('DELIVERED');
    expect(rows[0].channel).toBe('LOCAL');
  });

  it('is idempotent, because the client re-sends until we confirm', async () => {
    const rows = [row('e1:2026:D0')];
    const service = buildService(rows);
    const report = {
      dedupeKey: 'e1:2026:D0',
      outcome: 'SHOWN' as const,
      channel: 'LOCAL' as const,
      occurredAtUtc: AT,
    };

    await service.recordDelivery('u1', [report]);
    const second = await service.recordDelivery('u1', [report]);

    expect(second).toEqual({ recorded: 1, unknown: 0 });
    expect(rows[0].deliveryOutcome).toBe('SHOWN');
    expect(rows[0].deliveredAt?.toISOString()).toBe(AT);
  });

  /**
   * A person added offline has no occurrence and no reminder row on the server, but the
   * client still arms a local alarm for them (docs/M2_PLAN.md §1.3). That alarm firing is a
   * real delivery. Rejecting the report would hide precisely the offline case this metric
   * exists to measure.
   */
  it('accepts a report for a key the server has never materialised', async () => {
    const service = buildService([]);

    const result = await service.recordDelivery('u1', [
      { dedupeKey: 'unsynced:2026:D0', outcome: 'SHOWN', channel: 'LOCAL', occurredAtUtc: AT },
    ]);

    expect(result).toEqual({ recorded: 0, unknown: 1 });
  });

  it('cannot touch another user’s reminder', async () => {
    const rows = [row('e1:2026:D0', 'someone-else')];
    const service = buildService(rows);

    const result = await service.recordDelivery('u1', [
      { dedupeKey: 'e1:2026:D0', outcome: 'SHOWN', channel: 'FCM', occurredAtUtc: AT },
    ]);

    expect(result).toEqual({ recorded: 0, unknown: 1 });
    expect(rows[0].status).toBe('SENT');
    expect(rows[0].deliveryOutcome).toBeNull();
  });

  it('records a suppression without claiming a delivery', async () => {
    const rows = [row('e1:2026:D0')];
    const service = buildService(rows);

    await service.recordDelivery('u1', [
      { dedupeKey: 'e1:2026:D0', outcome: 'SUPPRESSED_QUIET', channel: 'FCM', occurredAtUtc: AT },
    ]);

    expect(rows[0].deliveryOutcome).toBe('SUPPRESSED_QUIET');
    expect(rows[0].status).toBe('SENT');
  });
});
