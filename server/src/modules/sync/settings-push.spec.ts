import { SyncPushSchema } from './sync.schemas';

/**
 * The settings blob as the Kotlin client actually serialises it.
 *
 * The client's Json is configured with `explicitNulls = false`, so a null field is OMITTED
 * from the payload rather than sent as `null`. That is invisible from the server side until
 * a required-but-nullable field meets it — and then every push with quiet hours switched off
 * is a 400, which is the DEFAULT state, so almost everything is rejected.
 *
 * It cost two rounds of device debugging during M2 because the visible symptom was "my edits
 * stopped syncing": validation runs before anything is applied, so a refused settings blob
 * took the people and events in the same batch with it. The client now pushes settings in a
 * request of their own; these cases are the other half of that fix.
 */
describe('the settings blob a real client sends', () => {
  const COMPLETE = {
    reminderHour: 9,
    ladderD7: true,
    ladderD1: true,
    ladderD0: true,
    digestEnabled: false,
    digestHour: 9,
    updatedAt: '2026-11-25T14:00:00.000Z',
  };

  it('accepts a blob with quiet hours OMITTED, and reads it as "off"', () => {
    const parsed = SyncPushSchema.safeParse({ settings: COMPLETE });
    expect(parsed.success).toBe(true);
    expect(parsed.data?.settings?.quietHoursStart).toBeNull();
    expect(parsed.data?.settings?.quietHoursEnd).toBeNull();
  });

  it('accepts an explicit null pair too, for a client that sends them', () => {
    const parsed = SyncPushSchema.safeParse({
      settings: { ...COMPLETE, quietHoursStart: null, quietHoursEnd: null },
    });
    expect(parsed.success).toBe(true);
    expect(parsed.data?.settings?.quietHoursStart).toBeNull();
  });

  it('accepts a complete window', () => {
    const parsed = SyncPushSchema.safeParse({
      settings: { ...COMPLETE, quietHoursStart: 22, quietHoursEnd: 7 },
    });
    expect(parsed.success).toBe(true);
    expect(parsed.data?.settings?.quietHoursStart).toBe(22);
  });

  /** Omission means "off", so only ONE end present is still a half-set window. */
  it('still refuses a half-set window', () => {
    expect(SyncPushSchema.safeParse({ settings: { ...COMPLETE, quietHoursStart: 22 } }).success)
      .toBe(false);
  });

  it('refuses timezone, which the device reports through POST /v1/devices', () => {
    expect(
      SyncPushSchema.safeParse({ settings: { ...COMPLETE, timezone: 'Asia/Kolkata' } }).success,
    ).toBe(false);
  });

  it('refuses a blob missing a field the client always sends', () => {
    const { digestHour: _omitted, ...incomplete } = COMPLETE;
    expect(SyncPushSchema.safeParse({ settings: incomplete }).success).toBe(false);
  });
});
