import { Body, Controller, Get, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { DraftingService } from './drafting.service';
import { WishHistoryService } from './wish-history.service';
import { DraftWishSchema, type DraftWishRequest, type DraftWishResponse } from './wishes.schemas';

@Controller('v1/wishes')
export class WishesController {
  constructor(
    private readonly drafting: DraftingService,
    private readonly history: WishHistoryService,
  ) {}

  /**
   * FR-WSH-01..04. One drafted message.
   *
   * POST rather than GET even though it reads nothing: it costs money, it is rate limited,
   * and it is not idempotent — Regenerate exists precisely to get a different answer to the
   * same question.
   *
   * This endpoint does NOT create a wish. FR-WSH-07 puts that after the hand-off, when the
   * user says they sent it, and the row arrives through the ordinary sync push like every
   * other record this app writes. A draft nobody sent is not history.
   */
  @Post('draft')
  @HttpCode(HttpStatus.OK)
  draft(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(DraftWishSchema)) body: DraftWishRequest,
  ): Promise<DraftWishResponse> {
    return this.drafting.draft(userId, body);
  }

  /**
   * FR-PPL-07's history list, for a client that has not synced yet.
   *
   * The app reads its own SQLite mirror in the ordinary case — the person screen draws with
   * the radio off — so this is for a fresh install that has a session before it has a full
   * pull, and for checking by hand what the server actually holds.
   */
  @Get('person/:personId')
  async forPerson(@CurrentUserId() userId: string, @Param('personId') personId: string) {
    const rows = await this.history.historyFor(userId, personId);
    return {
      wishes: rows.map((w) => ({
        id: w.id,
        personId: w.personId,
        type: w.eventType,
        occurrenceYear: w.occurrenceYear,
        sentiment: w.sentiment,
        eventId: w.eventId,
        messageText: w.messageText,
        tone: w.tone,
        language: w.language,
        channel: w.channel,
        source: w.source,
        sentAt: w.sentAt.toISOString(),
      })),
    };
  }
}
