import { durationToMs, hashToken } from './auth.service';

describe('durationToMs', () => {
  it.each([
    ['15m', 900_000],
    ['60d', 5_184_000_000],
    ['1h', 3_600_000],
    ['30s', 30_000],
    ['250ms', 250],
  ])('%s → %i ms', (input, expected) => {
    expect(durationToMs(input)).toBe(expected);
  });

  it('rejects a unit it does not understand, rather than guessing', () => {
    expect(() => durationToMs('2 weeks')).toThrow(/Unsupported duration/);
    expect(() => durationToMs('15')).toThrow(/Unsupported duration/);
  });
});

describe('hashToken', () => {
  it('is stable and does not echo the token', () => {
    const token = 'a-refresh-token';
    expect(hashToken(token)).toBe(hashToken(token));
    expect(hashToken(token)).not.toContain(token);
    expect(hashToken(token)).toHaveLength(64);
  });

  it('separates different tokens', () => {
    expect(hashToken('a')).not.toBe(hashToken('b'));
  });
});
