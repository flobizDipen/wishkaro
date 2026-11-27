import {
  DAY_MS,
  GENERATED_MEDIA_RETENTION_DAYS,
  RETENTION_WARNING_DAYS,
  SOURCE_PHOTO_RETENTION_DAYS,
  decide,
  expiryFor,
  type RetentionCandidate,
} from './retention.rules';

/**
 * FR-SET-06, clause by clause, at the boundaries.
 *
 * Definition-of-done item 5 asks for "the 90/180 boundaries hit **exactly**", which is the
 * kind of wording that gets tested at 89 and 200 and passes without ever touching the edge.
 * Every date case below is written as a pair — one millisecond on each side of the line —
 * because that is the only arrangement in which an off-by-one has nowhere to hide.
 *
 * These are the pure rules. `retention.db.spec.ts` drives the same rules through the real
 * database, the real blob store and the real queries, because `MILESTONE_CHECKLIST.md` §D is
 * explicit that the pure functions are not where this codebase breaks.
 */

const NOW = new Date('2026-11-27T10:00:00.000Z');

function photo(overrides: Partial<RetentionCandidate> = {}): RetentionCandidate {
  return {
    kind: 'SOURCE_PHOTO',
    retention: 'AUTO',
    expiresAt: new Date(NOW.getTime() + DAY_MS),
    purgeWarnedAt: null,
    purgedAt: null,
    attachedToSavedPoster: false,
    ...overrides,
  };
}

function poster(overrides: Partial<RetentionCandidate> = {}): RetentionCandidate {
  return { ...photo(), kind: 'POSTER', ...overrides };
}

describe('FR-SET-06 clause 1 — source photos, 90 days', () => {
  it('purges a photo whose expiry is exactly now, and not one a millisecond short', () => {
    expect(decide(photo({ expiresAt: NOW }), NOW)).toEqual({ action: 'PURGE' });

    expect(decide(photo({ expiresAt: new Date(NOW.getTime() + 1) }), NOW)).toEqual({
      action: 'SKIP',
      reason: 'NOT_DUE',
    });
  });

  it('puts the boundary at exactly 90 days from upload', () => {
    const uploadedAt = new Date('2026-08-29T10:00:00.000Z');
    const expiresAt = expiryFor('SOURCE_PHOTO', uploadedAt);

    // The arithmetic the requirement names, asserted rather than assumed.
    expect(expiresAt.getTime() - uploadedAt.getTime()).toBe(SOURCE_PHOTO_RETENTION_DAYS * DAY_MS);

    expect(decide(photo({ expiresAt }), expiresAt)).toEqual({ action: 'PURGE' });
    expect(decide(photo({ expiresAt }), new Date(expiresAt.getTime() - 1))).toEqual({
      action: 'SKIP',
      reason: 'NOT_DUE',
    });
  });

  /**
   * FR-SET-06's "unless attached to a saved poster".
   *
   * The one the build prompt calls out by name: "including a photo attached to a saved
   * poster, which must survive".
   */
  it('never purges a photo a saved poster was composited from, however overdue', () => {
    const ancient = new Date(NOW.getTime() - 10 * 365 * DAY_MS);

    expect(decide(photo({ expiresAt: ancient, attachedToSavedPoster: true }), NOW)).toEqual({
      action: 'SKIP',
      reason: 'ATTACHED_TO_POSTER',
    });
  });

  it('gets no warning — FR-SET-06 attaches that clause to generated media only', () => {
    // Inside the window where a POSTER would be warned, a photo does nothing at all.
    const expiresAt = new Date(NOW.getTime() + (RETENTION_WARNING_DAYS - 1) * DAY_MS);

    expect(decide(photo({ expiresAt }), NOW)).toEqual({ action: 'SKIP', reason: 'NOT_DUE' });
  });
});

describe('FR-SET-06 clause 2 — generated media, 180 days, with a warning', () => {
  it('puts the boundary at exactly 180 days from generation', () => {
    const madeAt = new Date('2026-05-31T10:00:00.000Z');
    const expiresAt = expiryFor('POSTER', madeAt);

    expect(expiresAt.getTime() - madeAt.getTime()).toBe(GENERATED_MEDIA_RETENTION_DAYS * DAY_MS);
  });

  it('warns when the window opens, and not a millisecond before', () => {
    const expiresAt = new Date(NOW.getTime() + RETENTION_WARNING_DAYS * DAY_MS);

    expect(decide(poster({ expiresAt }), NOW)).toEqual({ action: 'WARN' });

    expect(decide(poster({ expiresAt: new Date(expiresAt.getTime() + 1) }), NOW)).toEqual({
      action: 'SKIP',
      reason: 'NOT_DUE',
    });
  });

  /**
   * **The structural claim this milestone makes about the warning clause**, and the reason
   * `purge_warned_at` is a column rather than a log line: generated media cannot be destroyed
   * without notice, by construction, not by the job remembering to send something first.
   */
  it('refuses to purge generated media that was never warned, however overdue', () => {
    const expiresAt = new Date(NOW.getTime() - 365 * DAY_MS);

    expect(decide(poster({ expiresAt, purgeWarnedAt: null }), NOW)).toEqual({ action: 'WARN' });
  });

  it('refuses to purge until the full notice period has elapsed', () => {
    const expiresAt = new Date(NOW.getTime() - DAY_MS);

    // Warned yesterday: due, but the fortnight is not up.
    expect(
      decide(poster({ expiresAt, purgeWarnedAt: new Date(NOW.getTime() - DAY_MS) }), NOW),
    ).toEqual({ action: 'SKIP', reason: 'AWAITING_WARNING_PERIOD' });

    // Warned exactly RETENTION_WARNING_DAYS ago: the notice is served.
    expect(
      decide(
        poster({ expiresAt, purgeWarnedAt: new Date(NOW.getTime() - RETENTION_WARNING_DAYS * DAY_MS) }),
        NOW,
      ),
    ).toEqual({ action: 'PURGE' });

    // One millisecond short of it is not.
    expect(
      decide(
        poster({
          expiresAt,
          purgeWarnedAt: new Date(NOW.getTime() - RETENTION_WARNING_DAYS * DAY_MS + 1),
        }),
        NOW,
      ),
    ).toEqual({ action: 'SKIP', reason: 'AWAITING_WARNING_PERIOD' });
  });

  /**
   * A deployment whose sweep was switched off for a month must not then delete a year of
   * posters on the first night it comes back. This is the sequence, run as one test rather
   * than asserted as an intention.
   */
  it('gives a backlog the full notice period starting from the sweep that found it', () => {
    const expiresAt = new Date(NOW.getTime() - 300 * DAY_MS);
    const first = decide(poster({ expiresAt }), NOW);
    expect(first).toEqual({ action: 'WARN' });

    // The sweep records the warning at `now`. Thirteen days later: still nothing.
    const warnedAt = NOW;
    const thirteenDaysOn = new Date(NOW.getTime() + 13 * DAY_MS);
    expect(decide(poster({ expiresAt, purgeWarnedAt: warnedAt }), thirteenDaysOn)).toEqual({
      action: 'SKIP',
      reason: 'AWAITING_WARNING_PERIOD',
    });

    const fourteenDaysOn = new Date(NOW.getTime() + RETENTION_WARNING_DAYS * DAY_MS);
    expect(decide(poster({ expiresAt, purgeWarnedAt: warnedAt }), fourteenDaysOn)).toEqual({
      action: 'PURGE',
    });
  });
});

describe('FR-SET-06 clause 3 — both user-overridable to keep', () => {
  it('skips a kept asset of either kind, however overdue, warned or not', () => {
    const ancient = new Date(NOW.getTime() - 1000 * DAY_MS);

    for (const candidate of [
      photo({ retention: 'KEEP', expiresAt: ancient }),
      poster({ retention: 'KEEP', expiresAt: ancient, purgeWarnedAt: ancient }),
    ]) {
      expect(decide(candidate, NOW)).toEqual({ action: 'SKIP', reason: 'KEPT_BY_USER' });
    }
  });

  it("beats the attachment rule's ordering too — KEEP is checked first", () => {
    // Not a behaviour difference (both skip) but an ordering one: a kept photo reports that
    // the USER kept it, not that a poster happens to be holding it. The two have different
    // meanings on screen and only one of them survives the poster being deleted.
    expect(
      decide(photo({ retention: 'KEEP', attachedToSavedPoster: true, expiresAt: NOW }), NOW),
    ).toEqual({ action: 'SKIP', reason: 'KEPT_BY_USER' });
  });

  it('restores the original deadline when a kept asset is released, rather than minting a new one', () => {
    // The property `RetentionService.setRetention` relies on: un-keeping must not hand out
    // another full 90 or 180 days, which would make keep-then-unkeep an unlimited extension.
    const createdAt = new Date('2026-01-01T00:00:00.000Z');

    expect(expiryFor('SOURCE_PHOTO', createdAt).toISOString()).toBe('2026-04-01T00:00:00.000Z');
    expect(expiryFor('POSTER', createdAt).toISOString()).toBe('2026-06-30T00:00:00.000Z');
  });
});

describe('the rules refuse to act on anything they should not', () => {
  it('skips an asset that has already been purged, so a re-run is free', () => {
    expect(
      decide(photo({ expiresAt: new Date(NOW.getTime() - DAY_MS), purgedAt: NOW }), NOW),
    ).toEqual({ action: 'SKIP', reason: 'ALREADY_PURGED' });
  });

  /**
   * A null expiry is "no policy", never "expired at the beginning of time".
   *
   * Worth its own case because the two obvious ways of writing this rule disagree: in SQL a
   * null comparison is neither true nor false and the row silently drops out, while in
   * JavaScript `new Date(null) <= now` is TRUE, which would purge it.
   */
  it('skips an asset with no expiry rather than treating null as long overdue', () => {
    expect(decide(photo({ expiresAt: null }), NOW)).toEqual({ action: 'SKIP', reason: 'NO_EXPIRY' });
    expect(decide(poster({ expiresAt: null }), NOW)).toEqual({ action: 'SKIP', reason: 'NO_EXPIRY' });
  });
});
