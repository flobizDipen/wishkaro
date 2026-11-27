import { createHash } from 'node:crypto';
import { Inject, Injectable, Logger } from '@nestjs/common';
import type { EventType, PosterFormat, ThemeFamily } from '@prisma/client';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../prisma/prisma.service';
import { BLOB_STORE, blobKeyFor, type BlobStore } from '../media/blob-store';
import { aspectRatioOf, themeKeyFor } from './theme-key';
import type { PosterTheme } from './theme-library';

/**
 * FR-POS-03's cache. **The reason this milestone is cheap.**
 *
 * > "Generated background artwork is cached and reused. Cache key = (theme, event type,
 * > milestone, palette, aspect ratio). A popular theme is generated once and served
 * > thousands of times, driving marginal cost toward zero."
 *
 * ## The three properties definition-of-done item 5 asks for, and where each one lives
 *
 *  1. **A hit makes no model call.** `lookup()` returns bytes; the pipeline never reaches
 *     the provider. Here.
 *  2. **A hit writes no `ai_usage` row.** The ledger is written by the call path, and on a
 *     hit there is no call. In `poster-pipeline.service.ts`, by not happening.
 *  3. **A hit consumes no rate-limit slot.** `PosterRateLimitService.consume()` is called
 *     only on the live branch. In `poster-pipeline.service.ts`.
 *
 * The one thing a hit DOES write is `hit_count`, which is §13's "theme cache-hit rate"
 * health metric and the number TBD-3 and FR-MON-03a are both waiting on. That is a counter
 * on product art, not a ledger row for a generation that did not happen.
 *
 * ## The unique constraint, and the race it does not quite close
 *
 * §8's indexing note: "`theme_cache(theme_key)` must be unique or caching silently fails."
 * Without it two concurrent misses for one key insert two rows, a lookup picks one
 * arbitrarily, and the hit rate the whole economic thesis rests on goes quietly to zero
 * while every graph looks healthy.
 *
 * With it, the loser of that race gets a unique-violation instead. `store()` catches P2002
 * and re-reads, because the row that beat it is artwork for the same key and is therefore
 * exactly as good — there is no merge to do. What is NOT closed is the wasted generation:
 * both requests called the model before either inserted. Closing that needs a lock around a
 * fifteen-second call, which is a worse trade than paying for a duplicate image occasionally
 * — and it is bounded by one extra image per key, ever, because the second request onwards
 * finds the row.
 */

export interface ThemeCacheKeyParts {
  theme: PosterTheme;
  eventType: EventType;
  milestone: string | null;
  format: PosterFormat;
}

export interface CachedArtwork {
  themeKey: string;
  blobKey: string;
  png: Buffer;
}

@Injectable()
export class ThemeCacheService {
  private readonly logger = new Logger(ThemeCacheService.name);

  constructor(
    private readonly prisma: PrismaService,
    @Inject(BLOB_STORE) private readonly blobs: BlobStore,
  ) {}

  /** The five-part key, composed by the one function that composes it. */
  keyFor(parts: ThemeCacheKeyParts): string {
    return themeKeyFor({
      themeId: parts.theme.id,
      eventType: parts.eventType,
      milestone: parts.milestone,
      palette: parts.theme.palette,
      aspectRatio: aspectRatioOf(parts.format),
    });
  }

  /**
   * Artwork for this key, or null.
   *
   * **A row whose bytes are gone is a miss, not an error.** The local store does not survive
   * a deploy (`docs/M6_PLAN.md` §3.2), so after a redeploy every row points at nothing. The
   * honest behaviour is to regenerate and overwrite rather than to fail, and the log line is
   * how that shows up as the deployment problem it is rather than as a mystery.
   */
  async lookup(themeKey: string): Promise<CachedArtwork | null> {
    const row = await this.prisma.themeCache.findUnique({ where: { themeKey } });
    if (!row) return null;

    const png = await this.blobs.get(row.blobKey);
    if (!png) {
      this.logger.warn(
        `theme_cache row ${row.id} points at missing bytes (${row.blobKey}). ` +
          `Treating as a miss. If this is common, the blob store is not durable ` +
          `(store=${this.blobs.storeName}, durable=${this.blobs.isDurable}).`,
      );
      return null;
    }

    /**
     * `hit_count` is incremented without awaiting the read it belongs to, but IS awaited —
     * a fire-and-forget would make the metric lossy under exactly the load it is meant to
     * measure. It is one indexed UPDATE on a row already in cache.
     */
    await this.prisma.themeCache.update({
      where: { id: row.id },
      data: { hitCount: { increment: 1 } },
    });

    return { themeKey, blobKey: row.blobKey, png };
  }

  /**
   * Store artwork under this key and return it.
   *
   * `provider`/`model`/`promptVersion` are null for the shipped placeholder set, which is
   * not a gap: it is the honest record that no model produced it, the same distinction
   * `WishSource.TEMPLATE` makes for words.
   *
   * `approved` is left at its default of FALSE. TBD-5 is open, nothing in M6 can set it
   * honestly, and "hand-approved" names a hand that is not a session's.
   */
  async store(
    parts: ThemeCacheKeyParts,
    themeKey: string,
    png: Buffer,
    origin: { provider: string | null; model: string | null; promptVersion: string | null },
  ): Promise<CachedArtwork> {
    // Keyed by a hash of the cache key rather than by a uuid, so the bytes for one theme
    // key always land in one place even if the row is deleted and rewritten — and so an
    // operator looking at a bucket can match an object to a row without a join.
    const blobKey = blobKeyFor(
      'themes',
      createHash('sha256').update(themeKey).digest('hex').slice(0, 32),
      'png',
    );
    await this.blobs.put(blobKey, png, 'image/png');

    try {
      await this.prisma.themeCache.create({
        data: {
          themeKey,
          themeId: parts.theme.id,
          family: parts.theme.family as ThemeFamily,
          blobKey,
          eventType: parts.eventType,
          milestone: parts.milestone,
          palette: parts.theme.palette,
          aspectRatio: aspectRatioOf(parts.format),
          provider: origin.provider,
          model: origin.model,
          promptVersion: origin.promptVersion,
        },
      });
    } catch (cause) {
      if (cause instanceof Prisma.PrismaClientKnownRequestError && cause.code === 'P2002') {
        // Lost the race. The winner's artwork is for the same key, so it is exactly as
        // good; take it rather than overwriting and invalidating whatever is already
        // serving from it.
        this.logger.log(`Lost a theme_cache race on ${themeKey}; using the row that won.`);
        const existing = await this.lookup(themeKey);
        if (existing) return existing;
      }
      throw cause;
    }

    return { themeKey, blobKey, png };
  }

  /** §13's health metric, as one query. */
  async hitRateSince(since: Date): Promise<{ hits: number; misses: number; rate: number }> {
    const [hits, misses] = await Promise.all([
      this.prisma.generation.count({ where: { cacheHit: true, createdAt: { gte: since } } }),
      this.prisma.generation.count({ where: { cacheHit: false, createdAt: { gte: since } } }),
    ]);
    const total = hits + misses;
    return { hits, misses, rate: total === 0 ? 0 : hits / total };
  }
}
