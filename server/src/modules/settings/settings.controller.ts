import { Body, Controller, Get, HttpCode, HttpStatus, Patch } from '@nestjs/common';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RemindersScheduler } from '../reminders/reminders.scheduler';
import { SettingsPatchSchema, type SettingsDto, type SettingsPatch } from './settings.schemas';
import { SettingsService } from './settings.service';

@Controller('v1/me/settings')
export class SettingsController {
  constructor(
    private readonly settings: SettingsService,
    private readonly scheduler: RemindersScheduler,
  ) {}

  @Get()
  get(@CurrentUserId() userId: string): Promise<SettingsDto> {
    return this.settings.get(userId);
  }

  @Patch()
  @HttpCode(HttpStatus.OK)
  async patch(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(SettingsPatchSchema)) body: SettingsPatch,
  ): Promise<SettingsDto> {
    const { settings, rescheduleNeeded } = await this.settings.patch(userId, body);

    // Close the loop immediately rather than at the next ten-minute tick. Moving the
    // delivery hour from 09:00 to 08:00 at 08:30 has to stop this morning's reminder, not
    // the one after it — and a settings screen that visibly does nothing for ten minutes is
    // a settings screen nobody trusts.
    if (rescheduleNeeded) await this.scheduler.materialiseNow(userId);

    return settings;
  }
}
