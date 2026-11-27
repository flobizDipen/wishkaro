import { createHmac, timingSafeEqual } from 'node:crypto';
import { constants as fsConstants } from 'node:fs';
import { access, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { Injectable, Logger } from '@nestjs/common';
import type { BlobStore, SignedUrl, StoredBlob } from './blob-store';

/**
 * Bytes on this machine's disk. The committed default.
 *
 * Exists so that CI, a fresh clone and `npm run start:dev` all work with no cloud account —
 * the same reason `FakeLlmProvider` is the committed default provider. It is NOT a stand-in
 * that pretends to be durable: `isDurable` is false and the boot log says so, because
 * Railway's filesystem is ephemeral and a cache that empties on every deploy is a different
 * thing from the one FR-POS-03's economics assume.
 */
@Injectable()
export class LocalBlobStore implements BlobStore {
  readonly storeName = 'local';
  readonly isDurable = false;

  private readonly logger = new Logger(LocalBlobStore.name);
  private readonly root: string;

  constructor(
    root: string,
    /**
     * The HMAC key for [signedUrl]. Separate from the JWT secrets on purpose: a blob URL is a
     * capability for one object, it is handed to another app through a share sheet, and it
     * must not be minted by anything that can also mint a session.
     */
    private readonly signingSecret: string,
  ) {
    this.root = resolve(root);
  }

  /**
   * Two checks, and the first one was added because the second one alone passed a test it
   * should have failed.
   *
   * **The shape check.** A key this app mints is lowercase segments, digits, dashes,
   * underscores and dots, joined by `/` — `blobKeyFor()` is the only thing that makes one.
   * Anything else is refused here.
   *
   * It exists because of what the traversal check did with an ABSOLUTE key.
   * `join(root, '/etc/passwd')` is `<root>/etc/passwd`, which is safely inside the root — so
   * the resolved-path check passed it, correctly, and the write landed. The file never
   * escaped. But `/etc/passwd` and `etc/passwd` had become **the same object**, and in a
   * content cache two distinct keys silently sharing one entry is the whole bug class
   * FR-POS-03 depends on not having: §8's indexing note says `theme_cache(theme_key)` must be
   * unique "or caching silently fails", and a store that collapses two keys defeats that
   * constraint one layer below where it is enforced. Found by the test, not by reading.
   *
   * **The traversal check.** The resolved path has to be inside the root. Resolving first
   * rather than scanning the key for ".." is the version no encoding defeats, because
   * `resolve` has already collapsed everything by the time this compares. The trailing
   * separator matters: without it a root of `/srv/blobs` would accept `/srv/blobs-evil`.
   *
   * Both are kept. The shape check is the one that holds today; the traversal check is what
   * still holds if somebody widens the shape check later.
   */
  private pathFor(key: string): string {
    if (!SAFE_KEY.test(key)) {
      throw new Error('Blob key escapes the store root');
    }
    const full = resolve(join(this.root, key));
    if (full !== this.root && !full.startsWith(this.root + sep)) {
      throw new Error('Blob key escapes the store root');
    }
    return full;
  }

  async put(key: string, body: Buffer, contentType: string): Promise<StoredBlob> {
    const path = this.pathFor(key);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, body);
    // The type is stored alongside rather than guessed from the extension on read: the key
    // is opaque by contract, so there may not be an extension to guess from.
    await writeFile(`${path}.type`, contentType, 'utf8');
    return { key, bytes: body.byteLength, contentType };
  }

  async get(key: string): Promise<Buffer | null> {
    try {
      return await readFile(this.pathFor(key));
    } catch (cause) {
      if (isNotFound(cause)) return null;
      throw cause;
    }
  }

  async exists(key: string): Promise<boolean> {
    try {
      await access(this.pathFor(key), fsConstants.R_OK);
      return true;
    } catch {
      return false;
    }
  }

  async delete(key: string): Promise<void> {
    const path = this.pathFor(key);
    await rm(path, { force: true });
    await rm(`${path}.type`, { force: true });
  }

  /**
   * The same contract R2 would offer, implemented locally.
   *
   * The signature covers the key AND the expiry, so neither can be edited without
   * invalidating the other — a signature over the key alone would be a permanent token with
   * a decorative `expires` parameter next to it, which is the mistake this is written to
   * avoid.
   */
  async signedUrl(key: string, ttlSeconds: number): Promise<SignedUrl> {
    const expiresAt = new Date(Date.now() + ttlSeconds * 1000);
    const expires = Math.floor(expiresAt.getTime() / 1000);
    const signature = this.sign(key, expires);
    return {
      url: `/v1/media/${encodeURIComponent(key)}?expires=${expires}&sig=${signature}`,
      expiresAt,
    };
  }

  /** The other half of [signedUrl], used by the controller that serves the bytes. */
  verify(key: string, expires: number, signature: string): boolean {
    if (!Number.isFinite(expires) || expires * 1000 < Date.now()) return false;

    const expected = Buffer.from(this.sign(key, expires), 'utf8');
    const given = Buffer.from(signature, 'utf8');
    // Length first: timingSafeEqual throws on a mismatch, and the length is not a secret.
    if (expected.length !== given.length) return false;
    return timingSafeEqual(expected, given);
  }

  private sign(key: string, expires: number): string {
    return createHmac('sha256', this.signingSecret)
      .update(`${key}\n${expires}`)
      .digest('base64url');
  }

  logWhereItIs(): void {
    this.logger.warn(
      `Blob store is LOCAL at ${this.root}. It does NOT survive a redeploy: every poster and ` +
        'every cached theme background is lost when the container is replaced. Set BLOB_STORE=r2 ' +
        'once credentials exist — see docs/M6_PLAN.md §3.2.',
    );
  }
}

/**
 * The shape every key this app mints has: `segment/segment/name.ext`.
 *
 * No leading slash (an absolute key would collide with its relative twin — see `pathFor`), no
 * empty segment, no `..`, nothing outside `[a-z0-9._-]`. Deliberately narrow: the set of keys
 * this app produces is small and entirely ours, so the validator can be stricter than the
 * filesystem rather than as permissive as it.
 */
const SAFE_KEY = /^(?!.*\.\.)[a-z0-9][a-z0-9._-]*(?:\/[a-z0-9][a-z0-9._-]*)*$/;

function isNotFound(cause: unknown): boolean {
  return (
    typeof cause === 'object' && cause !== null && (cause as { code?: string }).code === 'ENOENT'
  );
}
