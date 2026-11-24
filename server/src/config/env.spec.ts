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
