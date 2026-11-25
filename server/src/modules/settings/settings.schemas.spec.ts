import { SettingsPatchSchema, toSettingsDto } from './settings.schemas';

const ok = (body: unknown) => SettingsPatchSchema.safeParse(body);
const issuePaths = (body: unknown) =>
  SettingsPatchSchema.safeParse(body).error?.issues.map((i) => i.path.join('.')) ?? [];

describe('SettingsPatchSchema', () => {
  it('accepts a single field, so one toggle does not resend the other seven', () => {
    expect(ok({ ladderD7: false }).success).toBe(true);
    expect(ok({}).success).toBe(true);
  });

  it('accepts every hour of the clock and nothing outside it', () => {
    for (let h = 0; h <= 23; h += 1) expect(ok({ reminderHour: h }).success).toBe(true);
    expect(ok({ reminderHour: 24 }).success).toBe(false);
    expect(ok({ reminderHour: -1 }).success).toBe(false);
    expect(ok({ reminderHour: 9.5 }).success).toBe(false);
  });

  it('rejects a field it does not know rather than dropping it', () => {
    expect(issuePaths({ ladderD3: true })).toEqual(['']);
  });

  /**
   * The one that matters. "Quiet from 22:00 until unspecified" has no safe reading, and the
   * unsafe one — treat the missing end as the end of time — silences every reminder the app
   * will ever send while the app continues to look like it is working.
   */
  describe('quiet hours are both or neither (FR-REM-09)', () => {
    it('accepts a complete window', () => {
      expect(ok({ quietHoursStart: 22, quietHoursEnd: 7 }).success).toBe(true);
    });

    it('accepts clearing both', () => {
      expect(ok({ quietHoursStart: null, quietHoursEnd: null }).success).toBe(true);
    });

    it('rejects a start with no end', () => {
      expect(issuePaths({ quietHoursStart: 22 })).toEqual(['quietHoursEnd']);
    });

    it('rejects an end with no start', () => {
      expect(issuePaths({ quietHoursEnd: 7 })).toEqual(['quietHoursStart']);
    });

    it('rejects clearing one half', () => {
      expect(issuePaths({ quietHoursStart: 22, quietHoursEnd: null })).toEqual(['quietHoursEnd']);
    });

    /** 22:00–22:00 is "never quiet" or "always quiet" depending which way you read it. */
    it('rejects a zero-width window instead of guessing', () => {
      expect(issuePaths({ quietHoursStart: 22, quietHoursEnd: 22 })).toEqual(['quietHoursEnd']);
    });

    it('leaves quiet hours alone when the patch does not mention them', () => {
      expect(ok({ digestEnabled: true }).success).toBe(true);
    });
  });
});

describe('toSettingsDto', () => {
  it('puts the settings clock on the wire as an instant', () => {
    const dto = toSettingsDto({
      reminderHour: 8,
      ladderD7: false,
      ladderD1: true,
      ladderD0: true,
      quietHoursStart: 22,
      quietHoursEnd: 7,
      digestEnabled: false,
      digestHour: 9,
      timezone: 'Asia/Kolkata',
      settingsUpdatedAt: new Date('2026-11-25T13:00:00.000Z'),
    });

    expect(dto).toEqual({
      reminderHour: 8,
      ladderD7: false,
      ladderD1: true,
      ladderD0: true,
      quietHoursStart: 22,
      quietHoursEnd: 7,
      digestEnabled: false,
      digestHour: 9,
      timezone: 'Asia/Kolkata',
      updatedAt: '2026-11-25T13:00:00.000Z',
    });
  });
});
