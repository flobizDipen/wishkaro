import { Injectable, Logger } from '@nestjs/common';
import type { DevicePlatform } from '@prisma/client';
import { PrismaService } from '../../prisma/prisma.service';
import { isValidTimezone } from '../../common/date/occurrence';
import { RemindersService } from '../reminders/reminders.service';

export interface RegisterDeviceInput {
  fcmToken: string;
  platform: DevicePlatform;
  timezone?: string | undefined;
}

export interface RegisterDeviceResult {
  deviceId: string;
  timezone: string;
  /** How many pending reminders moved because the timezone changed (FR-EVT-08). */
  remindersRescheduled: number;
}

@Injectable()
export class DevicesService {
  private readonly logger = new Logger(DevicesService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly reminders: RemindersService,
  ) {}

  /**
   * Registers or re-registers an FCM token.
   *
   * Keyed on the token, not the user: FCM reassigns a token to a different account when a
   * phone is handed over or the app is reinstalled, and sending the previous owner's
   * reminders to the new one would be a privacy incident.
   */
  async register(userId: string, input: RegisterDeviceInput): Promise<RegisterDeviceResult> {
    const device = await this.prisma.device.upsert({
      where: { fcmToken: input.fcmToken },
      create: {
        userId,
        fcmToken: input.fcmToken,
        platform: input.platform,
        timezone: input.timezone ?? null,
      },
      update: {
        userId,
        platform: input.platform,
        timezone: input.timezone ?? null,
        lastSeenAt: new Date(),
      },
    });

    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    let remindersRescheduled = 0;
    let timezone = user.timezone;

    if (input.timezone && isValidTimezone(input.timezone) && input.timezone !== user.timezone) {
      await this.prisma.user.update({ where: { id: userId }, data: { timezone: input.timezone } });
      timezone = input.timezone;
      remindersRescheduled = await this.reminders.rescheduleForTimezoneChange(userId);
      this.logger.log(`User ${userId} timezone ${user.timezone} → ${input.timezone}`);
    }

    return { deviceId: device.id, timezone, remindersRescheduled };
  }
}
