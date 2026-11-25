import { Injectable, Logger, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { toSettingsDto, type SettingsDto, type SettingsPatch } from './settings.schemas';

/**
 * Reminder settings (FR-REM-03, FR-REM-08, FR-REM-09, FR-SET-01).
 *
 * Deliberately NOT a fourth sync-cursor entity. There is exactly one settings row per user;
 * paging it would be absurd, and a cursor for a single row would mean a third ordering key
 * for every client to get right. Instead the blob rides on the `/v1/sync` pull response and
 * `settings_updated_at` decides whose copy is newer — the same last-write-wins rule the rest
 * of the data already uses, applied to one row.
 *
 * REQUIREMENTS.md §8's `user` sketch predates the reminder engine and lists none of these
 * columns; §8 is corrected rather than the schema bent to match it.
 */
@Injectable()
export class SettingsService {
  private readonly logger = new Logger(SettingsService.name);

  constructor(private readonly prisma: PrismaService) {}

  async get(userId: string): Promise<SettingsDto> {
    const user = await this.prisma.user.findFirst({ where: { id: userId, deletedAt: null } });
    if (!user) throw new NotFoundException({ code: 'USER_NOT_FOUND', message: 'No such user' });
    return toSettingsDto(user);
  }

  /**
   * Applies a patch and reports whether anything that moves a reminder changed.
   *
   * The caller needs that answer rather than a blanket "re-materialise everything": the
   * materialiser walks every event the user owns, and running it because somebody toggled a
   * notification channel they do not even have reminders for is work nobody asked for. The
   * three things that move an instant are the delivery hour, the ladder toggles and the quiet
   * window; `digestEnabled` changes which rows fire rather than when, and is included because
   * that is still a decision the scheduler has to re-make.
   */
  async patch(
    userId: string,
    patch: SettingsPatch,
  ): Promise<{ settings: SettingsDto; rescheduleNeeded: boolean }> {
    const before = await this.prisma.user.findFirst({ where: { id: userId, deletedAt: null } });
    if (!before) throw new NotFoundException({ code: 'USER_NOT_FOUND', message: 'No such user' });

    const after = await this.prisma.user.update({
      where: { id: userId },
      data: { ...patch, settingsUpdatedAt: new Date() },
    });

    const rescheduleNeeded =
      before.reminderHour !== after.reminderHour ||
      before.ladderD7 !== after.ladderD7 ||
      before.ladderD1 !== after.ladderD1 ||
      before.ladderD0 !== after.ladderD0 ||
      before.quietHoursStart !== after.quietHoursStart ||
      before.quietHoursEnd !== after.quietHoursEnd ||
      before.digestEnabled !== after.digestEnabled ||
      before.digestHour !== after.digestHour;

    if (rescheduleNeeded) {
      this.logger.log(`Settings changed for user ${userId}; pending reminders will be recomputed`);
    }
    return { settings: toSettingsDto(after), rescheduleNeeded };
  }
}
