import { Body, Controller, Get, Param, Patch } from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RetentionService } from './retention.service';

/**
 * FR-SET-06's user-facing half: *"both user-overridable to keep"*, and the warning somebody
 * has to be able to read.
 *
 * The sweep itself has no endpoint. It is a scheduled job, which is what §12 asks for in as
 * many words, and a route that ran it on demand would be a route that deletes a user's media
 * synchronously because somebody curled it.
 */
const SetRetentionSchema = z.object({
  retention: z.enum(['AUTO', 'KEEP']),
});

@Controller('v1/retention')
export class RetentionController {
  constructor(private readonly retention: RetentionService) {}

  /**
   * What is about to be removed, with the date and when the warning was recorded.
   *
   * FR-SET-06 says generated media is purged "with a warning". `decide()` makes the warning a
   * **precondition** of purging — no `purge_warned_at`, no deletion — and this is where that
   * fact becomes something a person can act on. One without the other is either a
   * notification with no teeth or a deletion nobody was told about.
   */
  @Get('warned')
  warned(@CurrentUserId() userId: string) {
    return this.retention.warned(userId);
  }

  @Patch(':assetId')
  setRetention(
    @CurrentUserId() userId: string,
    @Param('assetId') assetId: string,
    @Body(new ZodValidationPipe(SetRetentionSchema)) body: z.infer<typeof SetRetentionSchema>,
  ) {
    return this.retention.setRetention(userId, assetId, body.retention);
  }
}
