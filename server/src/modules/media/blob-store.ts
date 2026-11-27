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

// ---------------------------------------------------------------------------------------
// Minting keys
// ---------------------------------------------------------------------------------------

/**
 * The **only** thing that makes a blob key.
 *
 * `LocalBlobStore.pathFor` refuses anything that is not lowercase segments, digits, dashes,
 * underscores and dots joined by `/`, and its comment names this function as the sole
 * source. That check exists because of a real bug the spec found: an absolute key resolved
 * to the same file as its relative twin, which meant **two distinct keys silently sharing
 * one object** — the exact failure §8's indexing note warns about one layer up, where
 * `theme_cache(theme_key)` is unique "or caching silently fails".
 *
 * So keys are minted here, from values this app generates, and never assembled at a call
 * site from anything a client sent.
 *
 * Three namespaces, because the three have different lifetimes (FR-SET-06: source photos at
 * 90 days, generated media at 180, cached theme art never) and a prefix is what lets an
 * operator see which is which in a bucket listing without joining to Postgres.
 */
export type BlobNamespace = 'themes' | 'posters' | 'photos';

export function blobKeyFor(namespace: BlobNamespace, id: string, extension: string): string {
  /**
   * Validated BEFORE any normalisation.
   *
   * This used to lowercase first and then test, so an uppercase id never reached the throw
   * — it was silently rewritten, which is precisely the behaviour the comment below says
   * it refuses. `blobKeyFor('themes','AB12CD',...)` and `blobKeyFor('themes','ab12cd',...)`
   * returned the same key. No caller collides today (all three pass lowercase hex), but
   * the day one mints an id from a case-sensitive source — a base64url digest, a cuid, a
   * mixed-case theme id — two distinct ids would resolve to one object, `put` would
   * overwrite, and the unique constraint one layer up could not see it.
   */
  if (!/^[a-z0-9][a-z0-9-]*$/.test(id)) {
    // uuid v7, a hex digest, nothing else. Throwing rather than sanitising: a caller that
    // reached here with something else has a bug, and silently rewriting its id would put
    // two of its objects in one place.
    throw new Error(`Refusing to mint a blob key from "${id}"`);
  }
  if (!/^[a-z0-9]+$/.test(extension)) {
    throw new Error(`Refusing to mint a blob key with extension "${extension}"`);
  }
  const safeId = id;
  // Two characters of fan-out. A flat directory of a hundred thousand posters is slow to
  // list on every filesystem and some object stores still shard on the first bytes of a key.
  return `${namespace}/${safeId.slice(0, 2)}/${safeId}.${extension}`;
}
