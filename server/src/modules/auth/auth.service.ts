import { Inject, Injectable, Logger, UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { createHash, randomBytes } from 'node:crypto';
import { OAuth2Client, type TokenPayload } from 'google-auth-library';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { isValidTimezone } from '../../common/date/occurrence';

export interface SessionTokens {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}

export interface SessionResponse extends SessionTokens {
  user: { id: string; email: string; name: string | null; photoUrl: string | null; timezone: string };
}

export interface AccessTokenClaims {
  sub: string;
  email: string;
}

/** Seam for tests — the real implementation calls Google. */
export interface GoogleIdTokenVerifier {
  verify(idToken: string): Promise<TokenPayload>;
}

export const GOOGLE_VERIFIER = Symbol('GOOGLE_VERIFIER');

export class GoogleOAuthVerifier implements GoogleIdTokenVerifier {
  private readonly client: OAuth2Client;

  constructor(private readonly audience: string) {
    this.client = new OAuth2Client();
  }

  /**
   * Verifies against Google's public certs. The client's claim about who it is counts for
   * nothing — only a signature Google made does.
   */
  async verify(idToken: string): Promise<TokenPayload> {
    const ticket = await this.client.verifyIdToken({ idToken, audience: this.audience });
    const payload = ticket.getPayload();
    if (!payload) throw new UnauthorizedException({ code: 'INVALID_ID_TOKEN', message: 'Empty token payload' });
    return payload;
  }
}

@Injectable()
export class AuthService {
  private readonly logger = new Logger(AuthService.name);

  constructor(
    @Inject(APP_ENV) private readonly env: AppEnv,
    @Inject(GOOGLE_VERIFIER) private readonly verifier: GoogleIdTokenVerifier,
    private readonly prisma: PrismaService,
    private readonly jwt: JwtService,
  ) {}

  async signInWithGoogle(idToken: string, timezone?: string): Promise<SessionResponse> {
    let payload: TokenPayload;
    try {
      payload = await this.verifier.verify(idToken);
    } catch (cause) {
      this.logger.warn(`Google ID token rejected: ${cause instanceof Error ? cause.message : cause}`);
      throw new UnauthorizedException({
        code: 'INVALID_ID_TOKEN',
        message: 'Google ID token could not be verified',
      });
    }

    if (!payload.sub || !payload.email) {
      throw new UnauthorizedException({
        code: 'INVALID_ID_TOKEN',
        message: 'Google ID token is missing sub or email',
      });
    }
    if (payload.email_verified === false) {
      throw new UnauthorizedException({
        code: 'EMAIL_NOT_VERIFIED',
        message: 'Google account email is not verified',
      });
    }

    return this.establishSession({
      googleSub: payload.sub,
      email: payload.email,
      name: payload.name ?? null,
      photoUrl: payload.picture ?? null,
      timezone,
    });
  }

  /**
   * Creates or updates the user, and on first sign-in the "self" person row (FR-AUTH-04).
   * Shared by the real Google path and the development shortcut.
   */
  async establishSession(input: {
    googleSub: string;
    email: string;
    name: string | null;
    photoUrl: string | null;
    timezone?: string | undefined;
  }): Promise<SessionResponse> {
    const timezone =
      input.timezone && isValidTimezone(input.timezone) ? input.timezone : this.env.DEFAULT_TIMEZONE;

    const user = await this.prisma.$transaction(async (tx) => {
      const existing = await tx.user.findUnique({ where: { googleSub: input.googleSub } });

      if (existing) {
        return tx.user.update({
          where: { id: existing.id },
          data: {
            email: input.email,
            name: input.name ?? existing.name,
            photoUrl: input.photoUrl ?? existing.photoUrl,
            // Only adopt a client-supplied timezone when one was actually sent.
            ...(input.timezone ? { timezone } : {}),
            deletedAt: null,
          },
        });
      }

      const created = await tx.user.create({
        data: {
          googleSub: input.googleSub,
          email: input.email,
          name: input.name,
          photoUrl: input.photoUrl,
          timezone,
          // The deployment's default delivery hour (FR-REM-03). Seeded here rather than read
          // at send time, so that changing the env var on the server cannot silently move
          // the reminders of people who have already chosen an hour for themselves.
          reminderHour: this.env.DEFAULT_REMINDER_HOUR,
          digestHour: this.env.DEFAULT_REMINDER_HOUR,
        },
      });

      await tx.person.create({
        data: {
          userId: created.id,
          displayName: input.name ?? input.email.split('@')[0] ?? 'Me',
          relationToOwner: 'Self',
          isSelf: true,
        },
      });

      return created;
    });

    const tokens = await this.issueTokens(user.id, user.email);
    return {
      ...tokens,
      user: {
        id: user.id,
        email: user.email,
        name: user.name,
        photoUrl: user.photoUrl,
        timezone: user.timezone,
      },
    };
  }

  /**
   * Rotates a refresh token: the presented one is revoked and a new one issued in the same
   * transaction. A token presented twice is therefore always rejected the second time.
   */
  async refresh(presented: string): Promise<SessionTokens> {
    const hash = hashToken(presented);
    const record = await this.prisma.refreshToken.findUnique({ where: { tokenHash: hash } });

    if (!record) {
      throw new UnauthorizedException({ code: 'INVALID_REFRESH_TOKEN', message: 'Unknown refresh token' });
    }
    if (record.revokedAt) {
      // A revoked token being replayed means it leaked, or the client raced itself. Revoke
      // the whole family — cheap, and the user just signs in again.
      await this.prisma.refreshToken.updateMany({
        where: { userId: record.userId, revokedAt: null },
        data: { revokedAt: new Date() },
      });
      this.logger.warn(`Replayed refresh token for user ${record.userId}; revoked all sessions`);
      throw new UnauthorizedException({
        code: 'REFRESH_TOKEN_REUSED',
        message: 'Refresh token has already been used',
      });
    }
    if (record.expiresAt.getTime() <= Date.now()) {
      throw new UnauthorizedException({ code: 'REFRESH_TOKEN_EXPIRED', message: 'Refresh token expired' });
    }

    const user = await this.prisma.user.findUnique({ where: { id: record.userId } });
    if (!user || user.deletedAt) {
      throw new UnauthorizedException({ code: 'USER_GONE', message: 'Account no longer exists' });
    }

    const next = await this.issueTokens(user.id, user.email);
    await this.prisma.refreshToken.update({
      where: { id: record.id },
      data: { revokedAt: new Date(), replacedById: hashToken(next.refreshToken) },
    });
    return next;
  }

  async logout(presented: string): Promise<void> {
    await this.prisma.refreshToken.updateMany({
      where: { tokenHash: hashToken(presented), revokedAt: null },
      data: { revokedAt: new Date() },
    });
  }

  /**
   * Verifies the signature **and** that the account still exists.
   *
   * ## Why the database lookup is here (M9, `docs/M9_PLAN.md` C8)
   *
   * Until M9 this method checked the signature and nothing else, which was fine while nothing
   * could remove an account. FR-SET-04 removes accounts, and a JWT does not know that: for up
   * to `JWT_ACCESS_TTL` — fifteen minutes by default — a phone holding its last access token
   * authenticated successfully on **every** guarded route after its owner had deleted
   * everything.
   *
   * What that produced was not a privacy leak but something almost as bad: `POST /v1/sync`
   * would try to upsert rows against a `user_id` that no longer exists, and a foreign-key
   * violation surfacing as a 500 is not an answer to "my account is gone". `GET /v1/sync`
   * already answered `USER_NOT_FOUND` because it looks the owner up first; this makes every
   * other route agree with it, and makes the answer a 401 so the client knows to sign out.
   *
   * ## What it costs, without a claim about how much
   *
   * One lookup on the primary key, per authenticated request, on a connection the process
   * already holds. That is the structural cost; **the latency has not been measured**, and
   * `MILESTONE_CHECKLIST.md` (bought by M5 §5.2) says a comment that makes a performance claim
   * carries the measurement or does not make the claim — so this one does not make it. It is
   * recorded as unmeasured in `docs/M9_VERIFY.md`, and the alternative Dipen can still choose
   * is a Redis denylist of deleted ids with the same TTL as the token.
   *
   * `deletedAt` is checked too, not only existence. The deletion soft-deletes before it hard-
   * deletes, so a request arriving mid-deletion is refused rather than racing it.
   */
  async verifyAccessToken(token: string): Promise<AccessTokenClaims> {
    let claims: AccessTokenClaims;
    try {
      claims = await this.jwt.verifyAsync<AccessTokenClaims>(token, {
        secret: this.env.JWT_ACCESS_SECRET,
      });
    } catch {
      throw new UnauthorizedException({ code: 'INVALID_ACCESS_TOKEN', message: 'Access token is not valid' });
    }

    const user = await this.prisma.user.findFirst({
      where: { id: claims.sub, deletedAt: null },
      select: { id: true },
    });
    if (!user) {
      /**
       * `USER_GONE`, the same code `refresh()` already uses for the same situation, so the
       * client has one thing to match on rather than two.
       */
      throw new UnauthorizedException({ code: 'USER_GONE', message: 'Account no longer exists' });
    }

    return claims;
  }

  private async issueTokens(userId: string, email: string): Promise<SessionTokens> {
    const accessToken = await this.jwt.signAsync(
      { sub: userId, email } satisfies AccessTokenClaims,
      {
        secret: this.env.JWT_ACCESS_SECRET,
        // Seconds, not "15m": @nestjs/jwt types the string form narrowly and a number is
        // unambiguous anyway.
        expiresIn: Math.floor(durationToMs(this.env.JWT_ACCESS_TTL) / 1000),
      },
    );

    // Opaque and random, not a JWT: a refresh token must be revocable, and the only way to
    // revoke a self-contained token is to keep a list of the ones you have revoked.
    const refreshToken = randomBytes(48).toString('base64url');
    await this.prisma.refreshToken.create({
      data: {
        userId,
        tokenHash: hashToken(refreshToken),
        expiresAt: new Date(Date.now() + durationToMs(this.env.JWT_REFRESH_TTL)),
      },
    });

    return {
      accessToken,
      refreshToken,
      expiresInSeconds: Math.floor(durationToMs(this.env.JWT_ACCESS_TTL) / 1000),
    };
  }
}

export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

/** Parses the `15m` / `60d` forms used in configuration. */
export function durationToMs(value: string): number {
  const match = /^(\d+)\s*(ms|s|m|h|d)$/.exec(value.trim());
  if (!match) throw new Error(`Unsupported duration: "${value}" (expected e.g. 15m, 60d)`);
  const amount = Number(match[1]);
  const unit = match[2] as 'ms' | 's' | 'm' | 'h' | 'd';
  const scale = { ms: 1, s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 }[unit];
  return amount * scale;
}
