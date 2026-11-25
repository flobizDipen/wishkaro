import { Body, Controller, Get, HttpCode, HttpStatus, Post, Query } from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RemindersScheduler } from '../reminders/reminders.scheduler';
import { SyncService, type SyncPullResult, type SyncPushResult } from './sync.service';
import { SyncPullQuerySchema, SyncPushSchema } from './sync.schemas';

@Controller('v1/sync')
export class SyncController {
  constructor(
    private readonly sync: SyncService,
    private readonly scheduler: RemindersScheduler,
  ) {}

  @Get()
  pull(
    @CurrentUserId() userId: string,
    @Query(new ZodValidationPipe(SyncPullQuerySchema)) query: z.infer<typeof SyncPullQuerySchema>,
  ): Promise<SyncPullResult> {
    return this.sync.pull(userId, query.cursor, query.limit);
  }

  @Post()
  @HttpCode(HttpStatus.OK)
  async push(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(SyncPushSchema)) body: z.infer<typeof SyncPushSchema>,
  ): Promise<SyncPushResult> {
    const result = await this.sync.push(userId, body);
    // Close the loop immediately: a birthday added on the phone should be scheduled before
    // the user has finished putting the phone down.
    //
    // A settings blob counts, because the hour, the ladder toggles and the quiet window all
    // move the instants of reminders that already exist — a change made offline and synced
    // later must take effect on arrival, not at the next ten-minute tick. `body.settings`
    // rather than `result.settings`, since the result is also returned when the client's copy
    // LOST, and losing changed nothing.
    const settingsPushed = body.settings !== undefined && result.settings !== undefined;
    if (result.applied.events > 0 || result.applied.people > 0 || settingsPushed) {
      await this.scheduler.materialiseNow(userId);
    }
    return result;
  }
}
