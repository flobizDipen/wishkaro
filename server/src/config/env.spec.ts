import { parseEnv } from './env';

const BASE = {
  DATABASE_URL: 'postgresql://u:p@localhost:5432/db',
  REDIS_URL: 'redis://localhost:6379',
  GOOGLE_CLIENT_ID: '123.apps.googleusercontent.com',
  JWT_ACCESS_SECRET: 'x'.repeat(32),
  JWT_REFRESH_SECRET: 'y'.repeat(32),
  FIREBASE_PROJECT_ID: 'wishkaro-1c95e',
  FIREBASE_SERVICE_ACCOUNT: '{"project_id":"p"}',
};

describe('environment validation', () => {
  it('accepts a complete configuration', () => {
    const env = parseEnv(BASE);
    expect(env.PORT).toBe(3000);
    expect(env.DEFAULT_REMINDER_HOUR).toBe(9);
    expect(env.ALLOW_DEV_LOGIN).toBe(false);
  });

  it('refuses a short JWT secret', () => {
    expect(() => parseEnv({ ...BASE, JWT_ACCESS_SECRET: 'tooshort' })).toThrow(/at least 32/);
  });

  it('refuses to boot with no Firebase credentials at all', () => {
    const { FIREBASE_SERVICE_ACCOUNT: _drop, ...without } = BASE;
    expect(() => parseEnv(without)).toThrow(/FIREBASE_SERVICE_ACCOUNT/);
  });

  it('accepts a credentials FILE PATH as the alternative', () => {
    const { FIREBASE_SERVICE_ACCOUNT: _drop, ...without } = BASE;
    expect(() =>
      parseEnv({ ...without, GOOGLE_APPLICATION_CREDENTIALS: '/secrets/sa.json' }),
    ).not.toThrow();
  });

  it('REFUSES TO BOOT if the dev login is enabled in production', () => {
    expect(() => parseEnv({ ...BASE, NODE_ENV: 'production', ALLOW_DEV_LOGIN: 'true' })).toThrow(
      /forbidden when NODE_ENV=production/,
    );
  });

  it('allows the dev login in development', () => {
    expect(parseEnv({ ...BASE, NODE_ENV: 'development', ALLOW_DEV_LOGIN: 'true' }).ALLOW_DEV_LOGIN).toBe(
      true,
    );
  });

  it('rejects a reminder hour outside 0..23', () => {
    expect(() => parseEnv({ ...BASE, DEFAULT_REMINDER_HOUR: '25' })).toThrow();
  });
});

describe('M5 — the Anthropic key', () => {
  it('boots without one, because CI and a fresh clone have none', () => {
    expect(parseEnv(BASE).ANTHROPIC_API_KEY).toBeUndefined();
    expect(parseEnv(BASE).ANTHROPIC_MODEL).toBe('claude-opus-5-5');
    // Set explicitly: this model's own default is `medium`.
    expect(parseEnv(BASE).ANTHROPIC_EFFORT).toBe('low');
  });

  it('treats an EMPTY key as absent rather than refusing to boot', () => {
    // `.env.example` ships `ANTHROPIC_API_KEY=` with nothing after it. A copied-and-not-yet-
    // filled-in line arrives as "", and a `.min(1)` on it would refuse to start the server
    // for the most ordinary setup step there is.
    expect(parseEnv({ ...BASE, ANTHROPIC_API_KEY: '' }).ANTHROPIC_API_KEY).toBeUndefined();
    expect(parseEnv({ ...BASE, ANTHROPIC_API_KEY: '   ' }).ANTHROPIC_API_KEY).toBeUndefined();
    expect(parseEnv({ ...BASE, ANTHROPIC_API_KEY: 'sk-test' }).ANTHROPIC_API_KEY).toBe('sk-test');
  });

  it('rejects an effort level this model does not have', () => {
    expect(() => parseEnv({ ...BASE, ANTHROPIC_EFFORT: 'medium-high' })).toThrow();
  });

  it('refuses a rate-limit ceiling of zero', () => {
    // Zero would be "drafting is off", expressed as a limit nobody would read that way.
    expect(() => parseEnv({ ...BASE, WISH_DRAFT_LIMIT_HOUR: '0' })).toThrow();
  });
});
