import { z } from 'zod';

const Hour = z.number().int().min(0).max(23);

/**
 * The user's reminder settings (FR-REM-03, FR-REM-08, FR-REM-09).
 *
 * `.strict()` like every other write schema in this server, so a client sending a field this
 * build does not know about gets a 400 naming it rather than having it silently dropped.
 *
 * Everything is optional on a PATCH — a Settings screen toggling one switch should not have
 * to resend the other seven and risk clobbering a change made on another device between the
 * read and the write.
 */
export const SettingsPatchSchema = z
  .object({
    reminderHour: Hour.optional(),
    ladderD7: z.boolean().optional(),
    ladderD1: z.boolean().optional(),
    ladderD0: z.boolean().optional(),

    /**
     * Quiet hours (FR-REM-09). Both or neither.
     *
     * A half-set window is rejected rather than interpreted. "Quiet from 22:00 until
     * unspecified" has no safe reading, and the unsafe one — treat the missing end as the
     * end of time — silences every reminder the app will ever send, which is the single
     * worst thing this product can do while still appearing to work.
     */
    quietHoursStart: Hour.nullable().optional(),
    quietHoursEnd: Hour.nullable().optional(),

    digestEnabled: z.boolean().optional(),
    digestHour: Hour.optional(),
  })
  .strict()
  .superRefine((value, ctx) => {
    const startGiven = value.quietHoursStart !== undefined;
    const endGiven = value.quietHoursEnd !== undefined;

    // Only meaningful when the caller is touching the window at all. A PATCH that leaves
    // both out is not changing quiet hours and has nothing to validate.
    if (startGiven !== endGiven) {
      ctx.addIssue({
        code: 'custom',
        path: [startGiven ? 'quietHoursEnd' : 'quietHoursStart'],
        message:
          'quietHoursStart and quietHoursEnd must be sent together — a half-set window would ' +
          'silence every reminder',
      });
      return;
    }
    if (!startGiven) return;

    const start = value.quietHoursStart;
    const end = value.quietHoursEnd;
    if ((start === null) !== (end === null)) {
      ctx.addIssue({
        code: 'custom',
        path: ['quietHoursEnd'],
        message: 'clear quiet hours by sending both ends as null',
      });
      return;
    }
    if (start !== null && end !== null && start === end) {
      // 22:00–22:00 is either "no quiet hours" or "always quiet" depending on which way you
      // read the interval, and guessing wrong in the second direction is a silent app.
      ctx.addIssue({
        code: 'custom',
        path: ['quietHoursEnd'],
        message:
          'quiet hours cannot start and end at the same hour — send both as null to turn ' +
          'them off',
      });
    }
  });

export type SettingsPatch = z.infer<typeof SettingsPatchSchema>;

/** The shape the client stores and the sync pull carries. */
export interface SettingsDto {
  reminderHour: number;
  ladderD7: boolean;
  ladderD1: boolean;
  ladderD0: boolean;
  quietHoursStart: number | null;
  quietHoursEnd: number | null;
  digestEnabled: boolean;
  digestHour: number;
  timezone: string;
  updatedAt: string;
}

/** Every column the settings blob is made of. Named so a new one is a compile error here. */
export interface SettingsSource {
  reminderHour: number;
  ladderD7: boolean;
  ladderD1: boolean;
  ladderD0: boolean;
  quietHoursStart: number | null;
  quietHoursEnd: number | null;
  digestEnabled: boolean;
  digestHour: number;
  timezone: string;
  settingsUpdatedAt: Date;
}

/**
 * Pure, and in the schema module rather than the service, so the sync pull can build the
 * blob without importing a Nest provider — and so there is exactly one definition of what
 * "the settings" are on the wire.
 */
export function toSettingsDto(user: SettingsSource): SettingsDto {
  return {
    reminderHour: user.reminderHour,
    ladderD7: user.ladderD7,
    ladderD1: user.ladderD1,
    ladderD0: user.ladderD0,
    quietHoursStart: user.quietHoursStart,
    quietHoursEnd: user.quietHoursEnd,
    digestEnabled: user.digestEnabled,
    digestHour: user.digestHour,
    timezone: user.timezone,
    updatedAt: user.settingsUpdatedAt.toISOString(),
  };
}
