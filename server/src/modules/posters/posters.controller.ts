import { Body, Controller, Get, HttpCode, HttpStatus, Param, Post, Query } from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { PostersService } from './posters.service';
import {
  RequestPosterSchema,
  type GenerationStatusResponse,
  type PosterSummary,
  type RequestPosterRequest,
  type RequestPosterResponse,
} from './posters.schemas';

const EventTypeSchema = z.enum([
  'BIRTHDAY',
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
  'DEATH_ANNIVERSARY',
  'CUSTOM',
]);

/**
 * §9's poster surface: `GET /v1/themes`, `POST /v1/ai/poster`,
 * `GET /v1/ai/generation/:id` and `GET /v1/posters`.
 *
 * Three controllers' worth of routes in one class because they are one feature and the
 * prefixes differ only because §9 wrote them that way — `@Controller('v1')` with the full
 * paths below keeps the document and the code readable against each other.
 */
@Controller('v1')
export class PostersController {
  constructor(private readonly posters: PostersService) {}

  /**
   * FR-POS-04's library, **filtered for the occasion** (FR-POS-13).
   *
   * Takes a person and an event type rather than a sentiment. A client that could send
   * `sentiment=CELEBRATORY` could ask for confetti on a remembrance, so the one input that
   * decides the family is the one input the client does not get to supply — the same
   * decision `DraftWishSchema` took for tones.
   */
  @Get('themes')
  themes(
    @CurrentUserId() userId: string,
    @Query('personId') personId: string,
    @Query('eventType') eventType: string,
  ) {
    const type = EventTypeSchema.parse(eventType);
    return this.posters.themesForPerson(userId, personId, type);
  }

  /** FR-POS-09. Accepted and queued; the app polls the generation. */
  @Post('ai/poster')
  @HttpCode(HttpStatus.ACCEPTED)
  request(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(RequestPosterSchema)) body: RequestPosterRequest,
  ): Promise<RequestPosterResponse> {
    return this.posters.request(userId, body);
  }

  /** FR-POS-09's visible progress state. */
  @Get('ai/generation/:id')
  status(
    @CurrentUserId() userId: string,
    @Param('id') id: string,
  ): Promise<GenerationStatusResponse> {
    return this.posters.generationStatus(userId, id);
  }

  /** FR-HIS-01's "and the poster used", for a client that has not cached the file. */
  @Get('posters')
  forPerson(
    @CurrentUserId() userId: string,
    @Query('personId') personId: string,
  ): Promise<{ posters: PosterSummary[] }> {
    return this.posters.forPerson(userId, personId);
  }
}
