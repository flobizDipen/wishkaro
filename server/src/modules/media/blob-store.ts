/**
 * The one seam between this app and wherever bytes live.
 *
 * ## Why this is a port and not a Cloudflare R2 client
 *
 * `REQUIREMENTS.md` names R2 in three places — §8's `asset.r2_key` and `theme_cache.r2_key`,
 * §11's "R2 objects served via short-lived signed URLs", and §14's stack list — and R2 is
 * configured in **none** of them. There is no account, no bucket, no credential, nothing in
 * `env.ts` and nothing in `.env.example`. docs/BUILD_PROMPT_M6.md does not mention storage at
 * all, so a poster currently has nowhere to live; that is raised in docs/M6_PLAN.md §3.2
 * rather than treated as settled.
 *
 * So the column is `blob_key`, not `r2_key`, and the interface below is the whole surface.
 * Naming a schema column after one vendor's product is how a swap becomes a migration.
 *
 * ## What ships, and what deliberately does not
 *
 * `LocalBlobStore` is the committed default: a directory on disk, so CI and a fresh clone
 * work with no account and no key. That is the same arrangement `WISH_PROVIDER=fake` already
 * has and for the same reason — the mechanism tests have to run with nobody's credentials.
 *
 * **There is no R2 adapter in this commit, and that is a decision rather than an omission.**
 * `wishes.module.ts` made the identical call about Anthropic and wrote down why: "a branch
 * written for it would be one nothing can take and nothing can test — which is how a dead
 * path survives until the day it runs." An S3 adapter written against credentials nobody has
 * is type-checked fiction, and it would pull `@aws-sdk/client-s3` and
 * `@aws-sdk/s3-request-presigner` into a public repository's dependency tree to do it.
 * `BLOB_STORE=r2` is accepted by `env.ts` and throws **by name** in `MediaModule`, which is a
 * better failure than silently writing production posters to a container's ephemeral disk.
 *
 * ## The consequence nobody should discover later
 *
 * Railway's filesystem is ephemeral. Until an R2 adapter exists, **every poster and every
 * cached theme background is lost on redeploy** — which turns FR-POS-03's "generated once,
 * served thousands of times" into "generated once per deploy". That is a hole in the
 * deployment, not in this code, and `M6_VERIFY.md` says which of the two the cache-hit
 * measurement was taken against.
 */

/** What a store hands back after a write. */
export interface StoredBlob {
  key: string;
  bytes: number;
  contentType: string;
}

export interface SignedUrl {
  url: string;
  /** Absolute expiry, so a caller can cache the URL for less than its life rather than guess. */
  expiresAt: Date;
}

export interface BlobStore {
  /** A short name for logs and for `M6_VERIFY.md`: "local" or "r2". */
  readonly storeName: string;

  /**
   * True when this store survives a deploy.
   *
   * Not decoration. FR-POS-03's economics assume a cache that outlives a process, and a
   * measurement taken against a store that does not is a different claim. The boot log says
   * which one is running, so a deployment cannot quietly serve a cache that empties nightly.
   */
  readonly isDurable: boolean;

  put(key: string, body: Buffer, contentType: string): Promise<StoredBlob>;

  get(key: string): Promise<Buffer | null>;

  exists(key: string): Promise<boolean>;

  delete(key: string): Promise<void>;

  /**
   * A short-lived URL a phone can fetch (§11: "R2 objects served via short-lived signed
   * URLs").
   *
   * **On the port rather than only on the R2 adapter**, so the local store has to express the
   * same contract. If signing existed only where it was convenient, the two stores would
   * differ in exactly the way that matters: a poster is a picture of a named person, and a
   * guessable permanent path to one is the sort of thing that is discovered by somebody else.
   * `LocalBlobStore` therefore signs too, with the same HMAC the API already has secrets for.
   */
  signedUrl(key: string, ttlSeconds: number): Promise<SignedUrl>;
}

export const BLOB_STORE = Symbol('BLOB_STORE');

/**
 * How long a poster URL lives. Short, because the URL is the capability.
 *
 * Ten minutes is long enough to open a share sheet and for the receiving app to fetch the
 * image, and short enough that a URL pasted into a log or a bug report is dead by the time
 * anybody reads it.
 */
export const SIGNED_URL_TTL_SECONDS = 600;
