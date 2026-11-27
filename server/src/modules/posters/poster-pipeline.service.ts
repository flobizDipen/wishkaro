import { randomUUID } from 'node:crypto';
import { Inject, Injectable, Logger } from '@nestjs/common';
import type { EventType, ModerationVerdict, PosterFormat } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../config/env';
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
import { buildPosterCopy, solemnCopyViolation } from './poster-copy';
import {
  POSTER_ART_PROMPT_VERSION,
  POSTER_COPY_PROMPT_VERSION,
  buildArtPrompt,
  buildCopyPrompt,
  promptHashOf,
  userTextIn,
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
    @Inject(APP_ENV) private readonly env: AppEnv,
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
    // From `userTextIn`, the copy prompt's own declaration of what it carries, so the two
    // cannot drift. It used to be a hand-written `[displayName, notes]`, which checked a
    // field this prompt never sends and missed one it does.
    const inputCheck = await this.moderation.checkInput(
      userTextIn({
        displayName: person.displayName,
        relationToOwner: person.relationToOwner,
        eventType: job.eventType,
        sentiment: occasion.sentiment,
        milestone: occasion.milestone,
        language: 'english',
        themeIds: [],
        isMinor: person.isMinor,
      }),
    );
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

    // The template copy. The live path may replace it inside `resolveArtwork`, and only
    // if it passes the solemn gate and output moderation.
    let copy = buildPosterCopy({
      personName: person.displayName,
      eventType: job.eventType,
      sentiment: occasion.sentiment,
      milestone: occasion.milestone,
    });

    /**
     * FR-POS-01 (a) and (b), resolved together — and the ORDER is the fix.
     *
     * The first version ran the copy call unconditionally at step (a), before anything
     * looked at the cache, and gated only the image call. Two rules broke:
     *
     *  - **Definition-of-done item 5.** With a live text provider a cache HIT still made a
     *    model call and wrote an `ai_usage` row. The DB suite passed because it injects
     *    `FakeLlmProvider` (`isLive = false`), so the live configuration was never run.
     *  - **Section 10 rule 6.** `mayGenerateLive()` — the only caller of the spend cap and
     *    the poster limiter — was reachable only from the image path, so the copy call was
     *    bounded by nothing. Past the daily cap, every poster still billed for copy.
     *
     * Now each call has its OWN rail, and both rails start with the global cap (C8):
     *
     *  - `mayDraftCopy` governs the copy call and runs whatever the cache says, because
     *    the background key is person-independent and the copy is not.
     *  - `mayGenerateLive` governs the image call and is reached only on a cache MISS.
     *
     * So a hit pays for no image and takes no image slot — item 5's property for the call
     * that costs ₹1–15 — while FR-POS-01's step (a) keeps happening for everyone rather
     * than only the first person on each key. `M6_VERIFY.md` §0.7b records the trade and
     * why it was Dipen's to make.
     */
    const resolved = await this.resolveArtwork(job, theme, occasion, person, copy);
    theme = resolved.theme;
    copy = resolved.copy;
    const copyPromptHash = resolved.copyPromptHash;
    const background = resolved.background;

    /**
     * Section 10 rule 7, on the WORDS — not only on the picture.
     *
     * `drafting.service.ts` has run `checkOutputText` on every model-written wish since
     * M5. Poster copy was the one model-written string in this app that reached a user
     * unmoderated in either direction. Skipped when the words came from the template,
     * because the template is ours.
     */
    if (resolved.copyWasLive) {
      const copyCheck = await this.moderation.checkOutputText(
        [copy.headline, copy.subline].filter(Boolean).join(' '),
      );
      if (copyCheck.verdict === 'BLOCKED') {
        this.logger.warn(`Poster copy blocked by moderation: ${copyCheck.reasonCode}`);
        // Falls back to the template rather than failing: the user still gets a poster,
        // and rule 3's "then falls back to a template" is the same answer.
        copy = buildPosterCopy({
          personName: person.displayName,
          eventType: job.eventType,
          sentiment: occasion.sentiment,
          milestone: occasion.milestone,
        });
      }
    }

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
  private async resolveArtwork(
    job: PosterJob,
    initialTheme: PosterTheme,
    occasion: { sentiment: 'SOLEMN' | 'CELEBRATORY'; milestone: string | null },
    person: { displayName: string; relationToOwner: string | null; isMinor: boolean },
    templateCopy: { headline: string; subline: string | null },
  ): Promise<{
    theme: PosterTheme;
    copy: { headline: string; subline: string | null };
    copyWasLive: boolean;
    copyPromptHash: string | null;
    background: Background;
  }> {
    let theme = initialTheme;
    const partsFor = (t: PosterTheme) => ({
      theme: t,
      eventType: job.eventType,
      milestone: occasion.milestone,
      format: job.format,
    });

    /**
     * FR-POS-01(a), on its OWN rail — C8, decided by Dipen.
     *
     * The copy call runs whatever the background cache says, because the two have nothing
     * in common. The background key is `(theme, event type, milestone, palette, aspect
     * ratio)` and is deliberately person-independent — that is FR-POS-03's whole economic
     * thesis, one image serving thousands. The copy is per-person: the name, the relation,
     * the milestone. Coupling them, as the first fix for §0.7 did, dropped the personal
     * half exactly when the shared half succeeded, so the better the cache performed the
     * less AI copy anyone got.
     *
     * What that costs is item 5's literal wording: a cache hit may now make ONE text call.
     * §10's own table puts that at "fractions of a paisa" against ₹1–15 for an image, and
     * `mayGenerateLive` below is untouched, so the property that matters — a hit never
     * pays for a picture and never takes an image slot — still holds. `M6_VERIFY.md`
     * §0.7b records the trade.
     */
    let copy = templateCopy;
    let copyWasLive = false;
    let copyPromptHash: string | null = null;

    const copyGate = await this.mayDraftCopy(job.userId);
    if (copyGate.allowed) {
      const live = await this.draftCopy(job, person, occasion);
      if (live) {
        assertThemeAllowed(occasion.sentiment, live.copy.themeId);

        /**
         * The model's WORDS, held to the same rule as its theme.
         *
         * Taking the headline verbatim and re-validating only `themeId` is how a model
         * answering "Happy Birthday, Asha" for a death anniversary would have been
         * composited at 72px onto the poster the user sends. A violation falls back to
         * the template rather than failing the job.
         */
        const violation = solemnCopyViolation(
          occasion.sentiment,
          live.copy.headline,
          live.copy.subline,
        );
        if (violation) {
          this.logger.error(
            `Model returned celebratory copy ("${violation}") for a SOLEMN occasion; ` +
              'using the template. This is the failure FR-POS-13 exists for.',
          );
        } else {
          copy = { headline: live.copy.headline, subline: live.copy.subline };
          copyWasLive = true;
          copyPromptHash = live.promptHash;
        }
        theme = themeById(live.copy.themeId) ?? theme;
      }
    }

    /**
     * FR-POS-03's cache, consulted AFTER the copy call because the model may have chosen
     * the theme, and the theme is part of the key.
     *
     * This is where item 5's property lives: a hit returns here having paid for no image
     * and taken no image slot.
     */
    const themeKey = this.themeCache.keyFor(partsFor(theme));
    const hit = await this.themeCache.lookup(themeKey);
    if (hit) {
      return {
        theme,
        copy,
        copyWasLive,
        copyPromptHash,
        background: {
          png: hit.png,
          cacheHit: true,
          degraded: false,
          promptHash: null,
          promptVersion: null,
        },
      };
    }

    // --- the IMAGE rail, unchanged ----------------------------------------------------
    const imageGate = await this.mayGenerateLive(job.userId);
    if (!imageGate.allowed) {
      return {
        theme,
        copy,
        copyWasLive,
        copyPromptHash,
        background: await this.libraryBackground(
          theme,
          job.format,
          // `degraded` only when a RAIL bit. The ordinary no-key path is FR-POS-04's main
          // path, not a degradation.
          imageGate.reason === 'CAPPED' || imageGate.reason === 'RATE_LIMITED',
        ),
      };
    }

    // --- FR-POS-01(b) ------------------------------------------------------------------
    const background = await this.generateBackground(
      job,
      theme,
      occasion.sentiment,
      occasion.milestone,
      themeKey,
    );
    return { theme, copy, copyWasLive, copyPromptHash, background };
  }

  /**
   * FR-POS-04's pre-approved library, rendered on demand and **deliberately not cached**.
   *
   * The first version stored this gradient in `theme_cache` under the same key the real
   * artwork would use, and `lookup()` does not filter on provenance — so any key first
   * requested while `IMAGE_PROVIDER=fake` (the committed default), or while the cap or the
   * limiter was biting, was pinned to a placeholder for the life of the deployment. The
   * premium path for that key was dead, and the rows still reported `cacheHit = true,
   * degraded = false`, so the health metrics read fine.
   *
   * `theme_cache` is FR-POS-03's cache of **generated** artwork. The library is a different
   * thing — pre-approved themes that ship with the app — and conflating them is what caused
   * the poisoning. Rendering a gradient costs tens of milliseconds on the path that by
   * design spends nothing.
   */
  private async libraryBackground(
    theme: PosterTheme,
    format: PosterFormat,
    degraded: boolean,
  ): Promise<Background> {
    return {
      png: await renderLibraryArtwork(theme, format),
      cacheHit: false,
      // `degraded` only when a RAIL bit. The ordinary no-key path is not a degradation, it
      // is FR-POS-04's main path, and conflating the two would make the metric read 100%
      // on a healthy deployment.
      degraded,
      promptHash: null,
      promptVersion: null,
    };
  }

  private async generateBackground(
    job: PosterJob,
    theme: PosterTheme,
    sentiment: 'SOLEMN' | 'CELEBRATORY',
    milestone: string | null,
    themeKey: string,
  ): Promise<Background> {
    // The gate allowed a live call, but it may have allowed it for the COPY only.
    if (!this.images.isLive) {
      return this.libraryBackground(theme, job.format, false);
    }

    const parts = { theme, eventType: job.eventType, milestone, format: job.format };
    const prompt = buildArtPrompt({
      theme,
      eventType: job.eventType,
      // From the OCCASION, not re-derived from `theme.family`. They are one-to-one today
      // and `theme-library.ts` is explicit they may not stay so; on the day they diverge,
      // re-deriving here would hand a solemn occasion the celebratory register and drop
      // the BANNED_ON_SOLEMN negative list.
      sentiment,
      milestone,
    });
    const size = imageSizeFor(job.format);
    const result = await this.images.generate({
      prompt,
      size,
      quality: this.env.OPENAI_IMAGE_QUALITY,
    });

    /**
     * The ledger row, whatever happened. A refusal and an error write rows too, with
     * `imageCount` zero, so they cost nothing and still say a call was made.
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
      // Rule 3's equivalent for bytes: fall back to the library rather than fail.
      this.logger.warn(`Image generation ${result.outcome}; using the library artwork.`);
      return this.libraryBackground(theme, job.format, true);
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
   * Whether this request may make a live COPY call (C8).
   *
   * Its own rail, consulted on the cache-hit path as well as the miss path, because copy
   * is per-person and the background cache is not. Same order as everywhere else in this
   * app: the global cap first — a user nowhere near their own ceiling must not spend the
   * last rupee of the day — then the per-user window, so being capped does not also cost
   * them a slot.
   */
  private async mayDraftCopy(
    userId: string,
  ): Promise<{ allowed: boolean; reason: 'OK' | 'NOT_LIVE' | 'CAPPED' | 'RATE_LIMITED' }> {
    if (!this.llm.isLive) return { allowed: false, reason: 'NOT_LIVE' };

    if (await this.spendCap.isCapped()) {
      this.logger.warn('Global spend cap reached; poster copy falls back to the template.');
      return { allowed: false, reason: 'CAPPED' };
    }

    const verdict = await this.rateLimit.consumeCopy(userId);
    if (!verdict.allowed) return { allowed: false, reason: 'RATE_LIMITED' };

    return { allowed: true, reason: 'OK' };
  }

  /**
   * Whether this request may generate a live IMAGE.
   *
   * Reached only on a cache MISS, which is what keeps item 5's property true for the
   * expensive call: a hit never gets here, so it pays for no image and takes no image
   * slot. Copy is bounded separately — see `mayDraftCopy` and `M6_VERIFY.md` §0.7b.
   */
  private async mayGenerateLive(
    userId: string,
  ): Promise<{ allowed: boolean; reason: 'OK' | 'NOT_LIVE' | 'CAPPED' | 'RATE_LIMITED' }> {
    // The IMAGE provider only. Copy has its own rail (`mayDraftCopy`) since C8, so a
    // text-only deployment is bounded there rather than here.
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
