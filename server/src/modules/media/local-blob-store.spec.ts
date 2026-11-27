import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { LocalBlobStore } from './local-blob-store';
import { SIGNED_URL_TTL_SECONDS } from './blob-store';

/**
 * Against a REAL temporary directory, not a mocked `fs`.
 *
 * `MILESTONE_CHECKLIST.md` §D and M3 §6.3: a fake agrees with whatever the code does to it,
 * and everything worth asking here — does a traversing key escape, does a write land where
 * the read looks — is a question about the actual filesystem.
 */
describe('LocalBlobStore', () => {
  let root: string;
  let store: LocalBlobStore;

  beforeEach(async () => {
    root = await mkdtemp(join(tmpdir(), 'wishkaro-blob-'));
    store = new LocalBlobStore(root, 'test-signing-secret-at-least-32-characters-long');
  });

  afterEach(async () => {
    await rm(root, { recursive: true, force: true });
  });

  it('round-trips bytes', async () => {
    const body = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

    const stored = await store.put('posters/2026/abc.png', body, 'image/png');

    expect(stored).toEqual({ key: 'posters/2026/abc.png', bytes: 8, contentType: 'image/png' });
    expect(await store.get('posters/2026/abc.png')).toEqual(body);
    expect(await store.exists('posters/2026/abc.png')).toBe(true);
  });

  it('returns null rather than throwing for a key that was never written', async () => {
    // The poster pipeline asks "is this theme cached" by reading. A throw would make the
    // common path — a MISS, on the very first request for a theme — an error path.
    expect(await store.get('themes/never-written.png')).toBeNull();
    expect(await store.exists('themes/never-written.png')).toBe(false);
  });

  it('deletes', async () => {
    await store.put('a.png', Buffer.from('x'), 'image/png');
    await store.delete('a.png');
    expect(await store.exists('a.png')).toBe(false);
  });

  it('deleting something that is not there is not an error', async () => {
    // M9's retention sweep will re-run over rows whose bytes a previous run already took.
    await expect(store.delete('gone.png')).resolves.toBeUndefined();
  });

  describe('the root is a boundary, not a prefix', () => {
    // Keys reach this class from request bodies and database columns. Nothing in M6 produces
    // one of these; the point is that nothing in M7 can either.
    it.each([
      ['../escape.png'],
      ['posters/../../escape.png'],
      ['a/b/../../../escape.png'],
      // Absolute. This one PASSED the traversal check when it was the only check, because
      // `join(root, '/etc/passwd')` is inside the root — so nothing escaped, and
      // `/etc/passwd` and `etc/passwd` quietly became one object. Two keys sharing one
      // cache entry is precisely what §8's unique `theme_key` exists to prevent, defeated
      // a layer below where the constraint lives.
      ['/etc/passwd'],
      ['/posters/a.png'],
      // Empty segments and backslashes: not produced by `blobKeyFor`, so not accepted.
      ['posters//a.png'],
      ['posters\\..\\a.png'],
      [''],
    ])('refuses %s', async (key) => {
      await expect(store.put(key, Buffer.from('x'), 'image/png')).rejects.toThrow(
        /escapes the store root/,
      );
      await expect(store.get(key)).rejects.toThrow(/escapes the store root/);
    });

    it('a sibling directory sharing the root as a text prefix is still outside', async () => {
      // The trailing-separator case. Without it, a root of `/tmp/x` would accept `/tmp/x-evil`
      // because the resolved path starts with the root as a string.
      const sibling = new LocalBlobStore(root, 'secret-secret-secret-secret-secret');
      await expect(sibling.get('../' + root.split('/').pop() + '-evil/f.png')).rejects.toThrow(
        /escapes the store root/,
      );
    });

    it('writes land inside the root', async () => {
      await store.put('deep/nested/key.png', Buffer.from('hello'), 'image/png');
      expect(await readFile(join(root, 'deep/nested/key.png'), 'utf8')).toBe('hello');
    });
  });

  describe('signed URLs', () => {
    it('signs and verifies', async () => {
      await store.put('p.png', Buffer.from('x'), 'image/png');
      const { url, expiresAt } = await store.signedUrl('p.png', SIGNED_URL_TTL_SECONDS);

      const { expires, sig } = parse(url);
      expect(store.verify('p.png', expires, sig)).toBe(true);
      expect(expiresAt.getTime()).toBeGreaterThan(Date.now());
    });

    it('refuses a signature lifted onto a DIFFERENT key', async () => {
      // The failure this prevents: one legitimately obtained poster URL becoming a reader for
      // every poster in the store. The key is inside the signed payload for this reason.
      const { url } = await store.signedUrl('mine.png', SIGNED_URL_TTL_SECONDS);
      const { expires, sig } = parse(url);

      expect(store.verify('somebody-elses.png', expires, sig)).toBe(false);
    });

    it('refuses an expiry edited forward', async () => {
      // A signature over the key alone would be a permanent token with a decorative
      // `expires` next to it. Both are in the payload, so moving one breaks the other.
      const { url } = await store.signedUrl('p.png', SIGNED_URL_TTL_SECONDS);
      const { expires, sig } = parse(url);

      expect(store.verify('p.png', expires + 86_400, sig)).toBe(false);
    });

    it('refuses a URL whose expiry has passed', async () => {
      const { url } = await store.signedUrl('p.png', -1);
      const { expires, sig } = parse(url);

      expect(store.verify('p.png', expires, sig)).toBe(false);
    });

    it('refuses a garbage or absent signature without throwing', async () => {
      const { url } = await store.signedUrl('p.png', SIGNED_URL_TTL_SECONDS);
      const { expires } = parse(url);

      // `timingSafeEqual` throws on a length mismatch; the controller must get `false`, not
      // a 500 that tells a prober the difference between a bad length and a bad signature.
      expect(store.verify('p.png', expires, '')).toBe(false);
      expect(store.verify('p.png', expires, 'short')).toBe(false);
      expect(store.verify('p.png', Number.NaN, 'x')).toBe(false);
    });

    it('a different secret cannot verify', async () => {
      const { url } = await store.signedUrl('p.png', SIGNED_URL_TTL_SECONDS);
      const { expires, sig } = parse(url);

      const other = new LocalBlobStore(root, 'a-completely-different-secret-value-here');
      expect(other.verify('p.png', expires, sig)).toBe(false);
    });
  });

  it('declares itself non-durable', () => {
    // FR-POS-03's economics assume artwork is generated once and served thousands of times.
    // On an ephemeral filesystem it is generated once per DEPLOY, and `M6_VERIFY.md` has to
    // say which of the two the cache-hit measurement was taken against.
    expect(store.isDurable).toBe(false);
    expect(store.storeName).toBe('local');
  });
});

function parse(url: string): { expires: number; sig: string } {
  const query = new URLSearchParams(url.slice(url.indexOf('?') + 1));
  return { expires: Number(query.get('expires')), sig: query.get('sig') ?? '' };
}
