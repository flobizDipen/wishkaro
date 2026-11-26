import {
  BadRequestException,
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  Logger,
  NotFoundException,
} from '@nestjs/common';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { milestoneLabelFor } from '../events/derived';
import { AiUsageService, FEATURE_WISH_DRAFT } from './ledger/ai-usage.service';
import { LLM_PROVIDER, NO_TOKENS, type DraftResult, type LlmProvider } from './llm/llm-provider';
import { buildPrompt, type DraftContext } from './prompt-builder';
import { DraftRateLimitService } from './rate-limit/draft-rate-limit.service';
import { ToneNotAllowedError, assertToneAllowed, sentimentFor } from './tone-rules';
import { NO_REPEAT_YEARS, WishHistoryService } from './wish-history.service';
import type { DraftWishRequest, DraftWishResponse } from './wishes.schemas';

/**
 * One drafted wish (FR-WSH-01..04).
 *
 * The order of operations is the design, so it is stated once here and then followed:
 *
 *   1. Load the person. Everything the prompt knows comes from OUR tables, never from the
 *      request body — "derived values stay server-derived; the client never tells the server
 *      what something means" has been the rule since M0.
 *   2. Derive the sentiment and check the tone against it. A FUNNY draft for a death
 *      anniversary is refused here, before anything costs money, and refused rather than
 *      coerced so that a client carrying that bug finds out.
 *   3. Only then take a rate-limit slot. A 400 costs nothing, so it must not spend one of
 *      the user's fifteen.
 *   4. Call the model, and write a ledger row whatever happens.
 *
 * Nothing in this file logs a draft, a name or a note. §11 bans PII in logs, and a drafted
 * wish is prose about a named person — the one thing in this app that must never end up
 * greppable in a log aggregator.
 */
@Injectable()
export class DraftingService {
  private readonly logger = new Logger(DraftingService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly history: WishHistoryService,
    private readonly ledger: AiUsageService,
    private readonly rateLimit: DraftRateLimitService,
    @Inject(LLM_PROVIDER) private readonly provider: LlmProvider,
    @Inject(APP_ENV) private readonly env: AppEnv,
  ) {}

  async draft(userId: string, request: DraftWishRequest): Promise<DraftWishResponse> {
    const context = await this.contextFor(userId, request);

    const verdict = await this.rateLimit.consume(userId);
    if (!verdict.allowed) {
      // 429 with something the app can SHOW. Definition-of-done item 8: the limit has to
      // reach the user in words, or it is a button that silently does nothing.
      throw new HttpException(
        {
          code: 'RATE_LIMITED',
          message:
            verdict.window === 'day'
              ? "That's a lot of drafting for one day. You can write one yourself here, or come back tomorrow."
              : 'Give it a few minutes. You can write one yourself in the meantime.',
          retryAfterSeconds: verdict.retryAfterSeconds,
        },
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    const prompt = buildPrompt(context);
    const result = await this.callAndRecord(userId, prompt);

    if (result.outcome !== 'OK' || result.text.trim() === '') {
      /**
       * 503, and the app answers it with its own local template.
       *
       * Three different failures collapse here on purpose — a refusal, a provider error and
       * a timeout — because the user-facing answer to all three is the same and the
       * difference belongs in the ledger, not on the screen where somebody is writing about
       * a death. docs/M5_PLAN.md §4.2 and §5.3.
       *
       * The empty-text check is not paranoia: `stop_reason: "refusal"` arrives as an HTTP
       * 200, so code that reads `content` without checking first shows the user nothing at
       * all. The adapter checks; this is the second line.
       */
      this.logger.warn(
        `Draft unavailable for user ${userId}: ${result.outcome}` +
          (result.refusalCategory ? ` (${result.refusalCategory})` : ''),
      );
      throw new HttpException(
        {
          code: 'DRAFT_UNAVAILABLE',
          message: "We couldn't write this one.",
        },
        HttpStatus.SERVICE_UNAVAILABLE,
      );
    }

    return {
      text: result.text.trim(),
      tone: request.tone,
      language: request.language,
      // The server knows whether a model actually served this. A client that decided for
      // itself would write AI into the history for something a template produced, which is
      // the app lying in its own records.
      source: this.provider.isLive ? 'AI' : 'TEMPLATE',
      model: this.provider.isLive ? result.model : null,
      draftedAt: new Date().toISOString(),
    };
  }

  /**
   * The call, and the row.
   *
   * The `finally` is the point. docs/BUILD_PROMPT_M5.md: "Every model call writes a ledger
   * row. A call that cannot write one is a bug, not an optimisation." So a refusal, an
   * error and a thrown exception all leave a row behind, and a ledger write that itself
   * fails fails the request — because the alternative is a ledger with holes in it, which
   * cannot answer the question it exists for.
   */
  private async callAndRecord(
    userId: string,
    prompt: ReturnType<typeof buildPrompt>,
  ): Promise<DraftResult> {
    let result: DraftResult = {
      outcome: 'ERROR',
      text: '',
      model: this.env.ANTHROPIC_MODEL,
      usage: NO_TOKENS,
      errorMessage: 'provider threw',
    };

    try {
      // A provider is contracted never to throw — every outcome is a value. This still
      // catches, because "contracted not to" is not "cannot", and a provider bug must not
      // be the one path that skips the ledger.
      result = await this.provider.draft(prompt);
      return result;
    } catch (cause) {
      result = { ...result, errorMessage: String(cause) };
      return result;
    } finally {
      await this.ledger.record({
        userId,
        feature: FEATURE_WISH_DRAFT,
        provider: this.provider.providerName,
        model: result.model,
        outcome: result.outcome,
        usage: result.usage,
      });
    }
  }

  /** Everything the prompt is allowed to know, assembled from our own tables. */
  private async contextFor(userId: string, request: DraftWishRequest): Promise<DraftContext> {
    const person = await this.prisma.person.findFirst({
      where: { id: request.personId, userId, deletedAt: null },
      include: {
        traits: { where: { deletedAt: null } },
        events: { where: { deletedAt: null } },
      },
    });

    if (!person) {
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });
    }

    // The same expression `upsertEvent` uses, from the same function. Two implementations of
    // "is this solemn" is how a remembrance birthday comes out celebratory in one place and
    // solemn in another; FR-EVT-01a exists so there is one answer.
    const sentiment = sentimentFor(request.eventType, person.isDeceased);

    try {
      assertToneAllowed(sentiment, request.tone);
    } catch (cause) {
      if (cause instanceof ToneNotAllowedError) {
        throw new BadRequestException({
          code: 'TONE_NOT_ALLOWED',
          message: cause.message,
          details: [{ path: 'tone', message: cause.message, code: 'tone_not_allowed' }],
        });
      }
      throw cause;
    }

    this.assertPlausibleYear(request.occurrenceYear);

    const event = request.eventId
      ? person.events.find((e) => e.id === request.eventId)
      : person.events.find((e) => e.type === request.eventType);

    // Elapsed years, or null when the year was never known — a first-class case (FR-EVT-02)
    // that must not become a number anywhere, and must not become a vaguer sentence that
    // implies one either.
    const years = event?.year != null ? request.occurrenceYear - event.year : null;

    const previous = await this.history.previousWishes(
      userId,
      person.id,
      request.eventType,
      request.occurrenceYear,
      NO_REPEAT_YEARS,
    );

    return {
      displayName: person.displayName,
      relationToOwner: person.relationToOwner,
      eventType: request.eventType,
      sentiment,
      occurrenceYear: request.occurrenceYear,
      age: sentiment === 'SOLEMN' ? null : years,
      milestoneLabel: milestoneLabelFor(request.eventType, years, person.isDeceased),
      hobbies: person.traits.filter((t) => t.kind === 'HOBBY').map((t) => t.value),
      likes: person.traits.filter((t) => t.kind === 'LIKE').map((t) => t.value),
      notes: person.notes,
      isMinor: person.isMinor,
      tone: request.tone,
      language: request.language,
      previous,
      avoid: request.avoid,
    };
  }

  /**
   * The composer can legitimately be opened for NEXT year's birthday — a T-7 reminder in
   * December is about January — so ±1 rather than "this year". Wider than that is not a user
   * doing anything, and an unbounded year poisons the no-repeat query's ordering for ever.
   *
   * Checked against UTC rather than the owner's timezone on purpose: the window is two whole
   * years wide, so no timezone on earth can put a legitimate request outside it, and reading
   * the owner's zone here would be precision that means nothing.
   */
  private assertPlausibleYear(year: number): void {
    const thisYear = new Date().getUTCFullYear();
    if (Math.abs(year - thisYear) > 1) {
      throw new BadRequestException({
        code: 'VALIDATION_FAILED',
        message: `occurrenceYear ${year} is not within a year of now`,
        details: [{ path: 'occurrenceYear', message: 'out of range', code: 'out_of_range' }],
      });
    }
  }
}
