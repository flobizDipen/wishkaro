import { BadRequestException, Inject, Injectable, Logger, NotFoundException } from '@nestjs/common';
import type { Sentiment } from '@prisma/client';
import { PrismaService } from '../../prisma/prisma.service';
import { BLOB_STORE, SIGNED_URL_TTL_SECONDS, type BlobStore } from '../media/blob-store';
import { GenerationService } from './generation.service';
import { resolveOccasion } from './occasion';
import { PosterQueue } from './poster.queue';
import {
  ThemeNotAllowedError,
  assertThemeAllowed,
  themeById,
  themesFor,
  type PosterTheme,
} from './theme-library';
import type {
  GenerationStatusResponse,
  PosterSummary,
  RequestPosterRequest,
  RequestPosterResponse,
} from './posters.schemas';

/**
 * Intake and queries for posters. The pipeline itself is `poster-pipeline.service.ts`.
 *
 * The split is FR-POS-09's: "generation is asynchronous with a visible progress state". So
 * the request is validated synchronously — because a 400 the user can act on is worth more
 * than a FAILED row they have to poll for — and the expensive part runs on the queue.
 *
 * **What is validated here and not deferred:** the person exists and is theirs, the year is
 * plausible, and the theme is one the occasion's family allows. That last one is the
 * important one. FR-POS-13's filter is in the picker, in this service and in the pipeline,
 * and the reason for three is that the picker protects the user, this protects against a
 * client bug, and the pipeline protects against a replayed queue payload.
 */
@Injectable()
export class PostersService {
  private readonly logger = new Logger(PostersService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly generations: GenerationService,
    private readonly queue: PosterQueue,
    @Inject(BLOB_STORE) private readonly blobs: BlobStore,
  ) {}

  /** The themes an occasion may use (FR-POS-04's `GET /v1/themes`, filtered by FR-POS-13). */
  async themesForPerson(
    userId: string,
    personId: string,
    eventType: RequestPosterRequest['eventType'],
  ): Promise<{ sentiment: Sentiment; themes: PosterTheme[] }> {
    const person = await this.loadPerson(userId, personId);
    const occasion = resolveOccasion({
      person: { isDeceased: person.isDeceased, liveEventTypes: person.events.map((e) => e.type) },
      eventType,
      occurrenceYear: new Date().getUTCFullYear(),
      eventYear: null,
    });

    /**
     * Served from the SERVER's derivation, not the client's.
     *
     * The picker filters locally too — FR-POS-04 wants it to work offline — but a phone
     * whose sync has not caught up has a person row that still says alive, which is exactly
     * the window `454ea75` shipped in. This endpoint is the authority, and the client's
     * local filter is the thing that keeps working without it.
     */
    return { sentiment: occasion.sentiment, themes: themesFor(occasion.sentiment) };
  }

  /** FR-POS-09: accept, queue, and hand back something to poll. */
  async request(userId: string, body: RequestPosterRequest): Promise<RequestPosterResponse> {
    const person = await this.loadPerson(userId, body.personId);
    this.assertPlausibleYear(body.occurrenceYear);

    const occasion = resolveOccasion({
      person: { isDeceased: person.isDeceased, liveEventTypes: person.events.map((e) => e.type) },
      eventType: body.eventType,
      occurrenceYear: body.occurrenceYear,
      eventYear: null,
    });

    if (body.themeId) {
      if (!themeById(body.themeId)) {
        throw new BadRequestException({
          code: 'THEME_NOT_FOUND',
          message: `No theme "${body.themeId}"`,
          details: [{ path: 'themeId', message: 'unknown theme', code: 'not_found' }],
        });
      }
      try {
        assertThemeAllowed(occasion.sentiment, body.themeId);
      } catch (cause) {
        if (cause instanceof ThemeNotAllowedError) {
          // Refused, not coerced. A client that reached here has a bug, and the bug is one
          // that would put confetti on a remembrance — the single thing in this product
          // worth being loud about.
          throw new BadRequestException({
            code: 'THEME_NOT_ALLOWED',
            message: cause.message,
            details: [{ path: 'themeId', message: cause.message, code: 'theme_not_allowed' }],
          });
        }
        throw cause;
      }
    }

    const generation = await this.generations.open(userId, person.id);

    await this.queue.enqueue(
      {
        generationId: generation.id,
        userId,
        personId: person.id,
        eventId: body.eventId ?? null,
        eventType: body.eventType,
        occurrenceYear: body.occurrenceYear,
        themeId: body.themeId ?? null,
        format: body.format,
      },
      generation.createdAt,
    );

    return { generationId: generation.id, status: 'QUEUED' };
  }

  /** FR-POS-09's progress state. */
  async generationStatus(userId: string, id: string): Promise<GenerationStatusResponse> {
    const row = await this.prisma.generation.findFirst({
      where: { id, userId },
      include: { posters: { include: { asset: true }, take: 1, orderBy: { createdAt: 'desc' } } },
    });
    if (!row) {
      throw new NotFoundException({ code: 'NOT_FOUND', message: 'No such generation' });
    }

    const poster = row.posters[0];
    return {
      generationId: row.id,
      status: row.status,
      cacheHit: row.cacheHit,
      degraded: row.degraded,
      moderation: row.moderation,
      latencyMs: row.latencyMs,
      // A code a developer can act on. The app shows its own words; it never shows this.
      errorCode: row.errorCode,
      poster: poster ? await this.summarise(poster, poster.asset.blobKey) : null,
    };
  }

  /**
   * A person's posters (FR-HIS-01's "and the poster used").
   *
   * This exists rather than a fifth sync cursor — `docs/M6_PLAN.md` §4. A poster is
   * generated server-side and never edited, so last-write-wins on it is ceremony; what the
   * client needs offline is the FILE, which it caches, and the reference it needs already
   * syncs on `wish.poster_id`.
   */
  async forPerson(userId: string, personId: string): Promise<{ posters: PosterSummary[] }> {
    const rows = await this.prisma.poster.findMany({
      where: { userId, personId, deletedAt: null },
      include: { asset: true },
      orderBy: { createdAt: 'desc' },
      take: 50,
    });
    return {
      posters: await Promise.all(rows.map((p) => this.summarise(p, p.asset.blobKey))),
    };
  }

  private async summarise(
    poster: {
      id: string;
      personId: string;
      themeId: string;
      family: string;
      sentiment: string;
      eventType: string;
      format: string;
      attributed: boolean;
      createdAt: Date;
    },
    blobKey: string,
  ): Promise<PosterSummary> {
    // Signed and short-lived (§11). Minted per response rather than stored, because a URL
    // that outlived its signature in a database row would be a capability nobody could
    // revoke.
    let url: string | null = null;
    let urlExpiresAt: string | null = null;
    try {
      const signed = await this.blobs.signedUrl(blobKey, SIGNED_URL_TTL_SECONDS);
      url = signed.url;
      urlExpiresAt = signed.expiresAt.toISOString();
    } catch (cause) {
      this.logger.warn(`Could not sign ${blobKey}: ${String(cause)}`);
    }

    return {
      id: poster.id,
      personId: poster.personId,
      themeId: poster.themeId,
      family: poster.family,
      sentiment: poster.sentiment,
      eventType: poster.eventType,
      format: poster.format,
      attributed: poster.attributed,
      createdAt: poster.createdAt.toISOString(),
      url,
      urlExpiresAt,
    };
  }

  private async loadPerson(userId: string, personId: string) {
    const person = await this.prisma.person.findFirst({
      where: { id: personId, userId, deletedAt: null },
      include: { events: { where: { deletedAt: null } } },
    });
    if (!person) {
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });
    }
    return person;
  }

  /** The same ±1 window `DraftingService` uses, and for the same reason. */
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
