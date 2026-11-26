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
    const lines = result.error.issues.map((i) => `  - ${i.path.join('.') || '(root)'}: ${i.message}`);
    throw new Error(`Invalid environment configuration:\n${lines.join('\n')}`);
  }
  return result.data;
}
