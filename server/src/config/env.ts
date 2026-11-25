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
