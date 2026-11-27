import { z } from 'zod';

/**
 * Environment contract. The process refuses to boot if this does not parse — a server that
 * starts with half its configuration missing fails later, in production, at 09:00.
 */
const EnvSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    PORT: z.coerce.number().int().positive().default(3000),

    DATABASE_URL: z.string().min(1),
    REDIS_URL: z.string().min(1),

    /** The *Web* OAuth client id. Google issues ID tokens with this as the audience. */
    GOOGLE_CLIENT_ID: z.string().min(1),

    JWT_ACCESS_SECRET: z.string().min(32, 'JWT_ACCESS_SECRET must be at least 32 characters'),
    JWT_REFRESH_SECRET: z.string().min(32, 'JWT_REFRESH_SECRET must be at least 32 characters'),
    JWT_ACCESS_TTL: z.string().default('15m'),
    JWT_REFRESH_TTL: z.string().default('60d'),

    FIREBASE_PROJECT_ID: z.string().min(1),
    /** Local dev: absolute path to the service-account JSON, outside the repo. */
    GOOGLE_APPLICATION_CREDENTIALS: z.string().optional(),
    /** Railway and friends: the whole service-account JSON on one line. Wins when both are set. */
    FIREBASE_SERVICE_ACCOUNT: z.string().optional(),

    DEFAULT_REMINDER_HOUR: z.coerce.number().int().min(0).max(23).default(9),
    DEFAULT_TIMEZONE: z.string().default('Asia/Kolkata'),

    // ---- M5: wish drafting (FR-WSH-02, FR-WSH-08, FR-POS-10) --------------------------
    //
    /**
     * Which provider serves a draft. The choice is EXPLICIT, not inferred from which key
     * happens to be present.
     *
     * Key-presence magic would mean that pasting a key into `.env` to try something silently
     * changes what every draft costs and what `source` the history records. "fake" is the
     * default and stays the default until somebody says otherwise in writing — which is also
     * Dipen's instruction of 2026-11-26: keep the dummy for now.
     *
     * A provider named here without its key is a BOOT FAILURE, not a quiet fall back to the
     * fake: a deployment that was told to use OpenAI and serves templates instead is exactly
     * the lie this milestone keeps refusing to tell.
     */
    WISH_PROVIDER: z.enum(['fake', 'anthropic', 'openai']).default('fake'),
    //
    // The key is SERVER-SIDE ONLY. It never reaches the phone — a key in the APK is a key
    // anyone can extract, and this repository is public on top of that. It lives in
    // `~/.secrets/` locally (pointed at from a gitignored `.env`) and in Railway's own env.

    /**
     * Optional, deliberately, unlike the Firebase credentials next door.
     *
     * Without it the drafting endpoint runs on the deterministic fake provider: the ledger,
     * the rate limit, the tone rules and every error path still work and are still testable,
     * and a draft comes back marked as a template rather than as AI. Requiring it would mean
     * CI could not boot the app at all, and would make `npm run start:dev` impossible for
     * anyone who has not been handed a key — for a feature that is one endpoint out of
     * fifteen. The server LOGS loudly at boot when it is absent; see WishesModule.
     */
    //
    // An EMPTY string is treated as absent, not as a validation failure. `.env.example`
    // ships the key as `ANTHROPIC_API_KEY=` with nothing after it, and a copied-and-not-yet-
    // filled-in line arrives as "" rather than as undefined — so a bare `.min(1).optional()`
    // would refuse to boot the server for the most ordinary setup step there is.
    ANTHROPIC_API_KEY: z
      .string()
      .optional()
      .transform((v) => (v && v.trim() !== '' ? v : undefined)),

    /**
     * Default `claude-opus-5-5`, and changing it is Dipen's call, not a session's.
     * docs/BUILD_PROMPT_M5.md is explicit: measure the real cost with the ledger first and
     * bring him the number. Cheaper models exist and whether a greeting is a job for one of
     * them is a product decision with a quality cost attached.
     */
    ANTHROPIC_MODEL: z.string().default('claude-opus-5-5'),

    /**
     * Set EXPLICITLY because the default on this model is `medium`, and a two-sentence
     * birthday greeting does not need more than `low`. Thinking itself cannot be turned off
     * on `claude-opus-5-5` — a thinking budget and `{type:"disabled"}` are both a 400 at
     * every effort level — so this is the only control there is.
     */
    ANTHROPIC_EFFORT: z.enum(['low', 'medium', 'high', 'xhigh', 'max']).default('low'),

    /**
     * USD → INR, for the cost ledger. A configured number rather than a live rate: a ledger
     * that moved with the market would make two rows written an hour apart incomparable, and
     * the question this table answers — "what does a user cost per year" — needs a stable
     * unit more than it needs an accurate one. The rate used is stored ON EVERY ROW, so
     * changing this never re-prices anything already written.
     */
    USD_INR_RATE: z.coerce.number().positive().default(89),

    /**
     * FR-WSH-08's fair use. Text drafting is free to the user; it is not free to us, and
     * "free and unmetered" with no ceiling is an invitation.
     *
     * Counted on ATTEMPTS, not successes: a refused draft still cost money. Env-configurable
     * so the ceiling can move after the real per-draft cost is measured, without a code
     * change.
     */
    WISH_DRAFT_LIMIT_HOUR: z.coerce.number().int().positive().default(15),
    WISH_DRAFT_LIMIT_DAY: z.coerce.number().int().positive().default(50),

    /**
     * The GLOBAL daily spend cap, in INR (REQUIREMENTS.md §10 rule 6).
     *
     * The two limits above bound what ONE user can spend. This bounds the total. The
     * distinction matters because this repository is public, the endpoint is behind a token
     * anybody can mint by signing in with Google, and every call bills a personal card —
     * fifteen drafts an hour each is fair use and no limit at all across a thousand accounts.
     *
     * ₹200/day ≈ 13,000 drafts on the cheap tier or ~320 on Opus: generous for one person's
     * real use and a hard stop on anything that is not. Raise it deliberately, after reading
     * what the ledger says the day actually cost.
     */
    AI_DAILY_SPEND_CAP_INR: z.coerce.number().positive().default(200),

    // ---- OpenAI, added 2026-11-26 ------------------------------------------------------
    //
    // D20 in REQUIREMENTS.md said "Text AI = Claude". Dipen amended it after being shown the
    // conflict and chose BOTH providers behind one port, so the solemn copy TBD-4 has to
    // hand-review can be compared on real output rather than settled by picking a vendor.
    // The same rules apply as to the Anthropic key: server-side only, outside git.
    OPENAI_API_KEY: z
      .string()
      .optional()
      .transform((v) => (v && v.trim() !== '' ? v : undefined)),

    /**
     * Default `gpt-6-luna`: the cheapest current tier, and the one D20's own words call for
     * ("Haiku for high-volume wish drafting"). Its published rates are identical to Claude
     * Haiku 5.5's, which makes the two a clean comparison — the same prompt costs the same
     * on both, so a quality difference is the only difference.
     */
    OPENAI_MODEL: z.string().default('gpt-6-luna'),
    OPENAI_EFFORT: z.enum(['low', 'medium', 'high']).default('low'),

    // ---- M6: posters (FR-POS-01..13, REQUIREMENTS.md §10) -----------------------------

    /**
     * Where generated bytes live.
     *
     * EXPLICIT, like `WISH_PROVIDER`, and for the same reason: a store chosen by which
     * credentials happen to be present is a store that changes when somebody pastes a key in
     * to try something. `local` is the committed default so CI and a fresh clone work with no
     * cloud account.
     *
     * `r2` is accepted here and throws BY NAME in `MediaModule`. There is no R2 account, no
     * bucket and no credential on this machine, and `wishes.module.ts` already took this
     * decision once for Anthropic and wrote down why — an adapter written against
     * credentials nobody has is a dead path that survives until the day it runs. See
     * `media/blob-store.ts` and docs/M6_PLAN.md §3.2.
     */
    BLOB_STORE: z.enum(['local', 'r2']).default('local'),

    /**
     * Where `BLOB_STORE=local` writes. Gitignored — this repository is public and a poster is
     * a picture of a named person.
     *
     * **Not durable.** Railway replaces the container on every deploy, so this directory goes
     * with it. The boot log says so rather than leaving somebody to find out from a cache-hit
     * rate that resets nightly.
     */
    BLOB_LOCAL_ROOT: z.string().default('./.storage'),

    /**
     * HMAC key for the short-lived blob URLs §11 requires.
     *
     * Its own secret rather than a reuse of `JWT_ACCESS_SECRET`: a blob URL is a capability
     * for one object that gets handed to another app through a share sheet, and the thing
     * that mints it must not also be able to mint a session. Falls back to the access secret
     * only so a development server boots without a sixth secret to generate; production
     * should set it, and `.env.example` says so.
     */
    BLOB_SIGNING_SECRET: z
      .string()
      /**
       * The same floor the JWT secrets carry, and for a stronger reason: this one is the
       * SOLE authorisation for a `@Public` route. Without it `BLOB_SIGNING_SECRET=changeme`
       * booted cleanly, and theme blob keys are fully predictable from shipped data — the
       * key is `sha256(themeKey)` over ids and enum values anyone can read in this repo —
       * so a guessable secret is immediately usable there. The empty-string fallback to
       * `JWT_ACCESS_SECRET` is unaffected; it is the EXPLICIT short value this stops.
       */
      .min(32, 'BLOB_SIGNING_SECRET must be at least 32 characters')
      .optional()
      .or(z.literal(''))
      .transform((v) => (v && v.trim() !== '' ? v : undefined)),

    /**
     * Which provider generates background artwork (D21, §10).
     *
     * `fake` is the committed default and writes a deterministic placeholder image. There is
     * no `gpt-image-1` key on this machine, and the same rule as `WISH_PROVIDER` applies: a
     * provider named without its key is a BOOT FAILURE, not a quiet fall back — a deployment
     * told to generate art that silently serves placeholders is the lie this project keeps
     * refusing to tell.
     */
    IMAGE_PROVIDER: z.enum(['fake', 'openai']).default('fake'),

    /** D21 names it. Per-IMAGE pricing lives in `pricing.ts`; see `IMAGE_RATES` there. */
    OPENAI_IMAGE_MODEL: z.string().default('gpt-image-1'),

    /**
     * Quality tier, which is what actually decides the per-image price. §10's cost table
     * spans "₹1–15 depending on quality tier", so this is the single most expensive
     * configuration value in the app.
     */
    OPENAI_IMAGE_QUALITY: z.enum(['low', 'medium', 'high']).default('low'),

    /**
     * FR-POS-11's per-user limits, and they are NOT the drafting ones.
     *
     * Images are billed per image rather than per token, so the cost shape is nothing like
     * M5's: a user pressing "try another" four times is four whole images. Fifteen an hour —
     * the drafting ceiling — would be a day's budget from one person in twenty minutes.
     *
     * **A cache HIT consumes no slot at all.** FR-POS-03's whole economic argument is that
     * the common path costs nothing, and rationing the cheap path like the expensive one
     * throws that away. Only a live generation is counted, and it is counted on ATTEMPTS, as
     * M5's limiter is, because a refused image still cost money.
     *
     * Proposed rather than settled — docs/M6_PLAN.md §9 item 4 asks Dipen to confirm these
     * and to reconfirm AI_DAILY_SPEND_CAP_INR, which was sized against text.
     */
    POSTER_LIMIT_HOUR: z.coerce.number().int().positive().default(5),
    POSTER_LIMIT_DAY: z.coerce.number().int().positive().default(20),

    /**
     * The COPY call's own ceilings, separate from the image ceilings above (C8).
     *
     * Much higher, because the two cost three orders of magnitude apart: §10's own table
     * puts a text call at "fractions of a paisa" against ₹1–15 for an image. Sharing the
     * image ceiling would ration the cheap call to protect the budget for the expensive
     * one, which is the thing FR-POS-03 says not to do.
     */
    POSTER_COPY_LIMIT_HOUR: z.coerce.number().int().positive().default(60),
    POSTER_COPY_LIMIT_DAY: z.coerce.number().int().positive().default(400),

    /**
     * Moderation (§10 rule 7, FR-POS-12).
     *
     * `fake` records `UNCHECKED` and allows, which is honest: no verdict was reached. It does
     * NOT record ALLOWED, because a generation that nobody moderated has not been allowed by
     * anything — that distinction is the whole reason `ModerationVerdict.UNCHECKED` exists.
     *
     * `openai` needs the same key the image provider does, so rule 7 is structurally met and
     * not actually exercised until one arrives. docs/M6_PLAN.md §5.
     *
     * The deterministic INPUT check on the user's own typed text runs under every setting,
     * including `fake`, because it needs no key at all.
     */
    MODERATION_PROVIDER: z.enum(['fake', 'openai']).default('fake'),

    /**
     * Registers POST /v1/auth/dev-token, which mints a session without a real Google ID
     * token. Deliberately absent from .env.example so a deployment cannot inherit it by
     * copy-paste, and refused outright in production below.
     */
    ALLOW_DEV_LOGIN: z
      .enum(['true', 'false'])
      .default('false')
      .transform((v) => v === 'true'),
  })
  .superRefine((env, ctx) => {
    if (!env.FIREBASE_SERVICE_ACCOUNT && !env.GOOGLE_APPLICATION_CREDENTIALS) {
      ctx.addIssue({
        code: 'custom',
        path: ['FIREBASE_SERVICE_ACCOUNT'],
        message:
          'Set FIREBASE_SERVICE_ACCOUNT (raw JSON) or GOOGLE_APPLICATION_CREDENTIALS (file path). ' +
          'Without one of them the server cannot send a single push.',
      });
    }
    /**
     * A provider named without its key is a boot failure.
     *
     * The tempting alternative — fall back to the fake and log it — is how a deployment that
     * was told to call a model ends up serving templates for a week while somebody wonders
     * why the drafts read the same. The same reasoning as ALLOW_DEV_LOGIN below: refuse to
     * start rather than start wrong.
     */
    if (env.WISH_PROVIDER === 'openai' && !env.OPENAI_API_KEY) {
      ctx.addIssue({
        code: 'custom',
        path: ['OPENAI_API_KEY'],
        message: 'WISH_PROVIDER=openai needs OPENAI_API_KEY. Set it, or set WISH_PROVIDER=fake.',
      });
    }
    if (env.WISH_PROVIDER === 'anthropic' && !env.ANTHROPIC_API_KEY) {
      ctx.addIssue({
        code: 'custom',
        path: ['ANTHROPIC_API_KEY'],
        message:
          'WISH_PROVIDER=anthropic needs ANTHROPIC_API_KEY. Set it, or set WISH_PROVIDER=fake.',
      });
    }
    /**
     * The same rule, for the image provider. A deployment told to generate art and quietly
     * serving placeholders is worse than one that will not start.
     */
    if (env.IMAGE_PROVIDER === 'openai' && !env.OPENAI_API_KEY) {
      ctx.addIssue({
        code: 'custom',
        path: ['OPENAI_API_KEY'],
        message:
          'IMAGE_PROVIDER=openai needs OPENAI_API_KEY (gpt-image-1 bills per image). ' +
          'Set it, or set IMAGE_PROVIDER=fake.',
      });
    }
    if (env.MODERATION_PROVIDER === 'openai' && !env.OPENAI_API_KEY) {
      ctx.addIssue({
        code: 'custom',
        path: ['OPENAI_API_KEY'],
        message:
          'MODERATION_PROVIDER=openai needs OPENAI_API_KEY. Set it, or set ' +
          'MODERATION_PROVIDER=fake — which records UNCHECKED rather than claiming a verdict.',
      });
    }
    if (env.ALLOW_DEV_LOGIN && env.NODE_ENV === 'production') {
      ctx.addIssue({
        code: 'custom',
        path: ['ALLOW_DEV_LOGIN'],
        message:
          'ALLOW_DEV_LOGIN=true is forbidden when NODE_ENV=production. The dev login mints a ' +
          'session for any caller; it must never be reachable on a deployed server.',
      });
    }
  });

export type AppEnv = z.infer<typeof EnvSchema>;

export const APP_ENV = Symbol('APP_ENV');

/** Reads `.env` when present. In Docker and on Railway the environment is already populated. */
export function loadDotEnv(): void {
  try {
    process.loadEnvFile();
  } catch {
    // No .env file — expected in a container. Real env vars are already set.
  }
}

export function parseEnv(source: NodeJS.ProcessEnv = process.env): AppEnv {
  const result = EnvSchema.safeParse(source);
  if (!result.success) {
    const lines = result.error.issues.map(
      (i) => `  - ${i.path.join('.') || '(root)'}: ${i.message}`,
    );
    throw new Error(`Invalid environment configuration:\n${lines.join('\n')}`);
  }
  return result.data;
}
