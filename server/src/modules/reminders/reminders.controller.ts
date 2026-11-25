import { Body, Controller, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { DeliveryReportBatchSchema, type DeliveryReportBatch } from './delivery.schemas';
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

  /**
   * What actually happened to reminders on this device (FR-REM-10).
   *
   * Batched because the device is often offline at the moment that matters — the reports
   * queue on the phone and drain later — and that is the whole point: a reminder delivered
   * by the local alarm during an outage is exactly the delivery the G1 metric must count,
   * and it cannot be reported until the network comes back.
   */
  @Post('delivery')
  @HttpCode(HttpStatus.OK)
  async delivery(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(DeliveryReportBatchSchema)) body: DeliveryReportBatch,
  ): Promise<{ recorded: number; unknown: number }> {
    return this.reminders.recordDelivery(userId, body.reports);
  }
}
