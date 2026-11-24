import { Controller, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RemindersService } from './reminders.service';

@Controller('v1/reminders')
export class RemindersController {
  constructor(private readonly reminders: RemindersService) {}

  /**
   * Queues a reminder two minutes out (FR-REM-07).
   *
   * This is the verification loop for the whole chain: without it, proving that a push
   * actually lands means waiting until 09:00 tomorrow.
   */
  @Post('test')
  @HttpCode(HttpStatus.ACCEPTED)
  async test(@CurrentUserId() userId: string): Promise<{
    reminderId: string;
    scheduledAtUtc: string;
    dedupeKey: string;
    message: string;
  }> {
    const queued = await this.reminders.queueTestReminder(userId);
    return {
      reminderId: queued.reminderId,
      scheduledAtUtc: queued.scheduledAtUtc.toISOString(),
      dedupeKey: queued.dedupeKey,
      message: 'Queued. The send worker runs every 30s, so expect it within ~2 minutes.',
    };
  }
}
