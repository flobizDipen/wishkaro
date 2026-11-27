import { randomUUID } from 'node:crypto';
import { Inject, Injectable, Logger } from '@nestjs/common';
import type { EventType, ModerationVerdict, PosterFormat } from '@prisma/client';
import { PrismaService } from '../../prisma/prisma.service';
import { BLOB_STORE, blobKeyFor, type BlobStore } from '../media/blob-store';
import { ModerationService } from '../moderation/moderation.service';
import { AiUsageService } from '../wishes/ledger/ai-usage.service';
import { SpendCapService } from '../wishes/ledger/spend-cap.service';
import { LLM_PROVIDER, type LlmProvider } from '../wishes/llm/llm-provider';
import { composePoster } from './compositor';
import { FEATURE_POSTER, GenerationService } from './generation.service';
import { IMAGE_PROVIDER, type ImageProvider } from './image/image-provider';
import { renderLibraryArtwork } from './library-artwork';
import { resolveOccasion } from './occasion';
import { buildPosterCopy } from './poster-copy';
import {
  POSTER_ART_PROMPT_VERSION,
  POSTER_COPY_PROMPT_VERSION,
  buildArtPrompt,
  buildCopyPrompt,
  promptHashOf,
} from './poster-prompt';
import { isPremium } from './premium';
import { PosterRateLimitService } from './rate-limit/poster-rate-limit.service';
import { ThemeCacheService } from './theme-cache.service';
import {
  assertThemeAllowed,
  defaultThemeFor,
  themeById,
  themesFor,
  type PosterTheme,
} from './theme-library';

/**
 * FR-POS-01's three steps, in order, once per job.
 *
 *   (a) copy and theme — a text model picks or writes the words and selects a theme;
 *   (b) background artwork ONLY — `gpt-image-1`, nothing else;
 *   (c) compositing — the person's **unmodified** photo, the copy and the frame over it.
 *
 * ## The shape of the whole thing, because the order is the design
 *
 * 1. Load the person **with their live events**, and resolve the occasion. This is where
 *    the milestone's most dangerous bug would live and `occasion.ts` is why it does not.
 * 2. Choose the theme from the occasion's own family, and refuse one that is not in it.
 * 3. Input moderation (§10 rule 7), before anything costs money.
 * 4. **Look in the cache.** A hit ends the expensive half here: no model call, no ledger
 *    row, no rate-limit slot (definition-of-done item 5).
 * 5. On a miss, decide whether a live call is even allowed — the global spend cap first,
 *    then the per-user limiter — and degrade to the library when either bites (§10 rule 6).
 * 6. Composite, moderate the output, store, write the rows.
 *
 * ## What "degraded" means and why it is a separate column from "cacheHit"
 *
 * Both end in the same picture, and only one of them means the budget ran out. "How often
 * did we degrade" and "how often did we hit the cache" are different questions, and a
 * single boolean answers neither.
 */

export interface PosterJob {
  generationId: string;
  userId: string;
  personId: string;
  eventId: string | null;
  eventType: EventType;
  occurrenceYear: number;
  /** The theme the user picked, or null to take the occasion's default. */
  themeId: string | null;
  format: PosterFormat;
}

export interface PosterJobResult {
  posterId: string | null;
  status: 'SUCCEEDED' | 'FAILED' | 'BLOCKED';
}

/** What the background came from, and at what cost. */
interface Background {
  png: Buffer;
  cacheHit: boolean;
  degraded: boolean;
  promptHash: string | null;
  promptVersion: string | null;
}

@Injectable()
export class PosterPipelineService {
  private readonly logger = new Logger(PosterPipelineService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly generations: GenerationService,
    private readonly themeCache: ThemeCacheService,
    private readonly ledger: AiUsageService,
    private readonly spendCap: SpendCapService,
    private readonly rateLimit: PosterRateLimitService,
    private readonly moderation: ModerationService,
    @Inject(BLOB_STORE) private readonly blobs: BlobStore,
    @Inject(IMAGE_PROVIDER) private readonly images: ImageProvider,
    @Inject(LLM_PROVIDER) private readonly llm: LlmProvider,
  ) {}

  async run(job: PosterJob, startedAt: Date): Promise<PosterJobResult> {
    await this.generations.markRunning(job.generationId);

    /**
     * Loaded INSIDE the job, with the person's live events in the same query.
     *
     * This is the ordering `cb5919d` fixed for wishes, applied here. A poster request that
     * arrives in the same window as a new death anniversary therefore reads the rows as
     * they are now, and `resolveOccasion` ORs the event types in so that a death
     * anniversary which exists in any form decides the sentiment — whether or not
     * `recompute` has caught up with `person.is_deceased`.
     */
    const person = await this.prisma.person.findFirst({
      where: { id: job.personId, userId: job.userId, deletedAt: null },
      include: { events: { where: { deletedAt: null } } },
    });
    if (!person) {
      await this.generations.fail(job.generationId, 'PERSON_NOT_FOUND', startedAt);
      return { posterId: null, status: 'FAILED' };
    }

    const event = job.eventId
      ? person.events.find((e) => e.id === job.eventId)
      : person.events.find((e) => e.type === job.eventType);

    const occasion = resolveOccasion({
      person: { isDeceased: person.isDeceased, liveEventTypes: person.events.map((e) => e.type) },
      eventType: job.eventType,
      occurrenceYear: job.occurrenceYear,
      eventYear: event?.year ?? null,
    });

    /**
     * The theme, from the occasion's own family.
     *
     * `assertThemeAllowed` throws rather than coercing. Silently swapping a forbidden theme
     * for an allowed one would hide a client bug for ever, and the client in question is
     * the one that decides what somebody sends to a grieving family. The request has
     * already been checked at the controller; this is the second gate, held because a job
     * can also be replayed from a queue whose payload predates a theme being retired.
     */
    const requested = job.themeId ? themeById(job.themeId) : undefined;
    if (job.themeId && !requested) {
      await this.generations.fail(job.generationId, 'THEME_NOT_FOUND', startedAt);
      return { posterId: null, status: 'FAILED' };
    }
    if (requested) assertThemeAllowed(occasion.sentiment, requested.id);
    let theme: PosterTheme = requested ?? defaultThemeFor(occasion.sentiment);

    // --- §10 rule 7, input ------------------------------------------------------------
    //
    // The person's own text, which is what reaches the copy prompt on the live path and
    // the picture on every path. The display name is checked because it is rendered at
    // 72px on something the user will send.
    const inputCheck = await this.moderation.checkInput([person.displayName, person.notes]);
    if (inputCheck.verdict === 'BLOCKED') {
      await this.generations.close(
        job.generationId,
        {
          status: 'BLOCKED',
          cacheHit: false,
          degraded: false,
          moderation: 'BLOCKED',
          promptHash: null,
          promptVersion: null,
          errorCode: inputCheck.reasonCode,
        },
        startedAt,
      );
      return { posterId: null, status: 'BLOCKED' };
    }

    // --- FR-POS-01(a): copy and theme -------------------------------------------------
    let copy = buildPosterCopy({
      personName: person.displayName,
      eventType: job.eventType,
      sentiment: occasion.sentiment,
      milestone: occasion.milestone,
    });
    let copyPromptHash: string | null = null;

    if (this.llm.isLive) {
      const live = await this.draftCopy(job, person, occasion);
      if (live) {
        copy = { headline: live.copy.headline, subline: live.copy.subline };
        copyPromptHash = live.promptHash;
        // Re-checked, because a model is an untrusted input like any other even when the
        // schema it was given was an enum over the allowed set.
        assertThemeAllowed(occasion.sentiment, live.copy.themeId);
        theme = themeById(live.copy.themeId) ?? theme;
      }
    }

    // --- FR-POS-03 / FR-POS-01(b): the background -------------------------------------
    const background = await this.background(job, theme, occasion.milestone);

    // --- FR-POS-01(c): compositing ----------------------------------------------------
    const photo = await this.photoFor(person.id, job.userId);
    const composited = await composePoster({
      background: background.png,
      photo,
      headline: copy.headline,
      subline: copy.subline,
      theme,
      format: job.format,
      // FR-POS-08: defaults to attributed, and M7 is the only thing that can change it.
      attributed: !(await isPremium(job.userId)),
    });

    // --- §10 rule 7, output -----------------------------------------------------------
    const outputCheck = await this.moderation.checkOutputImage(composited.png);
    if (outputCheck.verdict === 'BLOCKED') {
      this.logger.warn(`Poster blocked by output moderation: ${outputCheck.reasonCode}`);
      await this.generations.close(
        job.generationId,
        {
          status: 'BLOCKED',
          cacheHit: background.cacheHit,
          degraded: background.degraded,
          moderation: 'BLOCKED',
          promptHash: copyPromptHash ?? background.promptHash,
          promptVersion: background.promptVersion ?? POSTER_COPY_PROMPT_VERSION,
          errorCode: outputCheck.reasonCode,
        },
        startedAt,
      );
      return { posterId: null, status: 'BLOCKED' };
    }

    const posterId = await this.persist(job, person.id, theme, occasion.sentiment, composited);

    await this.generations.close(
      job.generationId,
      {
        status: 'SUCCEEDED',
        cacheHit: background.cacheHit,
        degraded: background.degraded,
        moderation: worst(inputCheck.verdict, outputCheck.verdict),
        promptHash: copyPromptHash ?? background.promptHash,
        promptVersion: background.promptVersion ?? POSTER_COPY_PROMPT_VERSION,
        errorCode: null,
      },
      startedAt,
    );

    return { posterId, status: 'SUCCEEDED' };
  }

  /**
   * The background, and the only place in this pipeline that can spend money.
   *
   * The order of the three gates is the same one `DraftingService` uses and for the same
   * reasons: the **cache** first, because a hit must cost nothing and consume nothing; then
   * the **global cap**, because a user nowhere near their own limit must still not spend
   * the last rupee of the day; then the **per-user limiter**, because being capped should
   * not also cost them one of their five.
   */
  private async background(
    job: PosterJob,
    theme: PosterTheme,
    milestone: string | null,
  ): Promise<Background> {
    const parts = { theme, eventType: job.eventType, milestone, format: job.format };
    const themeKey = this.themeCache.keyFor(parts);

    const hit = await this.themeCache.lookup(themeKey);
    if (hit) {
      return {
        png: hit.png,
        cacheHit: true,
        degraded: false,
        promptHash: null,
        promptVersion: null,
      };
    }

    // A miss. Everything below this line is the premium edge.
    const live = await this.mayGenerateLive(job.userId);

    if (!live.allowed) {
      const png = await renderLibraryArtwork(theme, job.format);
      await this.themeCache.store(parts, themeKey, png, {
        provider: null,
        model: null,
        promptVersion: null,
      });
      return {
        png,
        cacheHit: false,
        // `degraded` only when a RAIL bit. The ordinary no-key path is not a degradation,
        // it is FR-POS-04's main path, and conflating the two would make the degradation
        // metric read 100% on a healthy deployment.
        degraded: live.reason !== 'NOT_LIVE',
        promptHash: null,
        promptVersion: null,
      };
    }

    const prompt = buildArtPrompt({
      theme,
      eventType: job.eventType,
      sentiment: theme.family === 'SOLEMN' ? 'SOLEMN' : 'CELEBRATORY',
      milestone,
    });
    const size = imageSizeFor(job.format);
    const result = await this.images.generate({ prompt, size, quality: 'low' });

    /**
     * The ledger row, whatever happened.
     *
     * "Every model call writes a ledger row. A call that cannot write one is a bug, not an
     * optimisation." A refusal and an error write rows too — `imageCount` is zero on those,
     * so they cost ₹0 and still say that a call was made, which is the distinction a ledger
     * with gaps cannot express.
     */
    await this.ledger.record({
      userId: job.userId,
      feature: FEATURE_POSTER,
      provider: this.images.providerName,
      model: result.model,
      outcome: result.outcome,
      usage: { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
      billing: {
        kind: 'image',
        quality: result.quality,
        size: result.size,
        count: result.imageCount,
      },
      generationId: job.generationId,
      promptVersion: POSTER_ART_PROMPT_VERSION,
    });

    if (result.outcome !== 'OK' || !result.png) {
      // §10 rule 3's equivalent for bytes: fall back to the library rather than fail.
      this.logger.warn(`Image generation ${result.outcome}; using the library artwork.`);
      const png = await renderLibraryArtwork(theme, job.format);
      await this.themeCache.store(parts, themeKey, png, {
        provider: null,
        model: null,
        promptVersion: null,
      });
      return { png, cacheHit: false, degraded: true, promptHash: null, promptVersion: null };
    }

    await this.themeCache.store(parts, themeKey, result.png, {
      provider: this.images.providerName,
      model: result.model,
      promptVersion: POSTER_ART_PROMPT_VERSION,
    });

    return {
      png: result.png,
      cacheHit: false,
      degraded: false,
      promptHash: promptHashOf(prompt),
      promptVersion: POSTER_ART_PROMPT_VERSION,
    };
  }

  /**
   * Whether this request may make a live image call.
   *
   * Reached only on a cache MISS, which is what keeps definition-of-done item 5 true: a hit
   * never gets here, so it never consumes a rate-limit slot.
   */
  private async mayGenerateLive(
    userId: string,
  ): Promise<{ allowed: boolean; reason: 'OK' | 'NOT_LIVE' | 'CAPPED' | 'RATE_LIMITED' }> {
    if (!this.images.isLive) return { allowed: false, reason: 'NOT_LIVE' };

    if (await this.spendCap.isCapped()) {
      // §10 rule 6's "automatic degradation to the pre-approved theme library" — which M5
      // could only approximate with the phone's template, because no library existed.
      this.logger.warn('Global spend cap reached; this poster degrades to the theme library.');
      return { allowed: false, reason: 'CAPPED' };
    }

    const verdict = await this.rateLimit.consume(userId);
    if (!verdict.allowed) {
      return { allowed: false, reason: 'RATE_LIMITED' };
    }
    return { allowed: true, reason: 'OK' };
  }

  /** FR-POS-01 step (a) on the live path. Returns null on any failure; the template holds. */
  private async draftCopy(
    job: PosterJob,
    person: { displayName: string; relationToOwner: string | null; isMinor: boolean },
    occasion: { sentiment: 'SOLEMN' | 'CELEBRATORY'; milestone: string | null },
  ): Promise<{
    copy: { headline: string; subline: string | null; themeId: string };
    promptHash: string;
  } | null> {
    const allowed = themesFor(occasion.sentiment).map((t) => t.id);
    const prompt = buildCopyPrompt({
      displayName: person.displayName,
      relationToOwner: person.relationToOwner,
      eventType: job.eventType,
      sentiment: occasion.sentiment,
      milestone: occasion.milestone,
      language: 'english',
      themeIds: allowed,
      isMinor: person.isMinor,
    });

    const result = await this.llm.draftPosterCopy({ ...prompt, themeIds: allowed });

    await this.ledger.record({
      userId: job.userId,
      feature: FEATURE_POSTER,
      provider: this.llm.providerName,
      model: result.model,
      outcome: result.outcome,
      usage: result.usage,
      generationId: job.generationId,
      promptVersion: POSTER_COPY_PROMPT_VERSION,
    });

    if (result.outcome !== 'OK' || !result.copy) {
      // §10 rule 3: "falls back to a template". `buildPosterCopy` already wrote one.
      this.logger.warn(`Poster copy ${result.outcome}; using the template copy.`);
      return null;
    }
    return { copy: result.copy, promptHash: promptHashOf(`${prompt.system}\n${prompt.user}`) };
  }

  /**
   * The person's photo, or null.
   *
   * **Null is a first-class case, not an error** (FR-POS-06, OD-11). Most people in a
   * 600-person contact list have no photo, so for many users the typographic template is
   * not a fallback — it is the only path they will ever see.
   */
  private async photoFor(personId: string, userId: string): Promise<Buffer | null> {
    const asset = await this.prisma.asset.findFirst({
      where: { personId, userId, kind: 'SOURCE_PHOTO', deletedAt: null },
      orderBy: { createdAt: 'desc' },
    });
    if (!asset) return null;

    const bytes = await this.blobs.get(asset.blobKey);
    if (!bytes) {
      this.logger.warn(`Photo asset ${asset.id} has no bytes; compositing without a photo.`);
      return null;
    }
    return bytes;
  }

  /** The asset and poster rows, written together so a poster never points at nothing. */
  private async persist(
    job: PosterJob,
    personId: string,
    theme: PosterTheme,
    sentiment: 'SOLEMN' | 'CELEBRATORY',
    composited: { png: Buffer; width: number; height: number },
  ): Promise<string> {
    // A storage id, deliberately NOT the poster row's id: the row's id is minted by
    // Postgres (`uuid(7)`) and does not exist until the transaction below runs, and the
    // bytes have to be written before the row that points at them — otherwise a crash
    // between the two leaves a poster row whose image is missing, which is the one
    // inconsistency a user would actually see.
    const blobKey = blobKeyFor('posters', randomUUID().replace(/-/g, ''), 'png');
    await this.blobs.put(blobKey, composited.png, 'image/png');

    const poster = await this.prisma.$transaction(async (tx) => {
      const asset = await tx.asset.create({
        data: {
          userId: job.userId,
          personId,
          kind: 'POSTER',
          blobKey,
          contentType: 'image/png',
          bytes: composited.png.byteLength,
          width: composited.width,
          height: composited.height,
          // FR-SET-06: generated media is purged after 180 days. M6 writes the date; M9's
          // retention job is what reads it, so M9 is a job rather than a job and a
          // migration.
          expiresAt: new Date(Date.now() + 180 * 24 * 60 * 60 * 1000),
        },
      });

      return tx.poster.create({
        data: {
          userId: job.userId,
          personId,
          generationId: job.generationId,
          themeId: theme.id,
          family: theme.family,
          // Stored rather than re-derived on read: a poster is a fact about the moment it
          // was made, and it is the audit trail for the one failure this milestone is most
          // likely to produce — `family != CELEBRATORY WHERE sentiment = SOLEMN` is a query
          // rather than an inspection of every image.
          sentiment,
          eventType: job.eventType,
          format: job.format,
          assetId: asset.id,
          attributed: !(await isPremium(job.userId)),
        },
        select: { id: true },
      });
    });

    return poster.id;
  }
}

/**
 * The API's own size vocabulary for each of our formats (FR-POS-05).
 *
 * `gpt-image-1` renders three sizes and none of them is 1080×1920, so the background is
 * generated at the nearest aspect ratio and the compositor resizes it. That is a background,
 * not a face — FR-POS-02's rule is about likeness, and nothing here touches a photo.
 */
function imageSizeFor(format: PosterFormat): string {
  switch (format) {
    case 'STATUS':
      return '1024x1536';
    case 'SQUARE':
      return '1024x1024';
    case 'LINK':
      return '1536x1024';
  }
}

function worst(a: ModerationVerdict, b: ModerationVerdict): ModerationVerdict {
  if (a === 'BLOCKED' || b === 'BLOCKED') return 'BLOCKED';
  if (a === 'ALLOWED' && b === 'ALLOWED') return 'ALLOWED';
  return 'UNCHECKED';
}
