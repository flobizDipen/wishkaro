import {
  Body,
  Controller,
  HttpCode,
  HttpStatus,
  Inject,
  Logger,
  NotFoundException,
  Post,
} from '@nestjs/common';
import { z } from 'zod';
import { APP_ENV, type AppEnv } from '../../config/env';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { AuthService, type SessionResponse, type SessionTokens } from './auth.service';
import { Public } from './public.decorator';

const GoogleSignInSchema = z.object({
  idToken: z.string().min(1, 'idToken is required'),
  /** IANA zone reported by the device, e.g. "Asia/Kolkata". */
  timezone: z.string().min(1).optional(),
});

const RefreshSchema = z.object({ refreshToken: z.string().min(1) });

const DevTokenSchema = z.object({
  email: z.email().default('dev@wishkaro.local'),
  name: z.string().min(1).default('Dev User'),
  timezone: z.string().min(1).optional(),
});

@Controller('v1/auth')
export class AuthController {
  constructor(private readonly auth: AuthService) {}

  @Public()
  @Post('google')
  @HttpCode(HttpStatus.OK)
  google(
    @Body(new ZodValidationPipe(GoogleSignInSchema)) body: z.infer<typeof GoogleSignInSchema>,
  ): Promise<SessionResponse> {
    return this.auth.signInWithGoogle(body.idToken, body.timezone);
  }

  @Public()
  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  refresh(
    @Body(new ZodValidationPipe(RefreshSchema)) body: z.infer<typeof RefreshSchema>,
  ): Promise<SessionTokens> {
    return this.auth.refresh(body.refreshToken);
  }

  @Public()
  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  async logout(
    @Body(new ZodValidationPipe(RefreshSchema)) body: z.infer<typeof RefreshSchema>,
  ): Promise<void> {
    await this.auth.logout(body.refreshToken);
  }
}

/**
 * Development-only session minting, so the server is provable with curl before the Android
 * client exists (docs/M0_PLAN.md §1.4).
 *
 * Two independent gates: NODE_ENV must be `development` AND ALLOW_DEV_LOGIN must be `true`.
 * The configuration layer additionally refuses to boot at all if the flag is set while
 * NODE_ENV=production, so this cannot be switched on by an environment mistake.
 */
@Controller('v1/auth')
export class DevAuthController {
  private readonly logger = new Logger(DevAuthController.name);

  constructor(
    @Inject(APP_ENV) private readonly env: AppEnv,
    private readonly auth: AuthService,
  ) {}

  @Public()
  @Post('dev-token')
  @HttpCode(HttpStatus.OK)
  async devToken(
    @Body(new ZodValidationPipe(DevTokenSchema)) body: z.infer<typeof DevTokenSchema>,
  ): Promise<SessionResponse> {
    if (!this.env.ALLOW_DEV_LOGIN || this.env.NODE_ENV !== 'development') {
      // 404 rather than 403: a disabled endpoint should not advertise that it exists.
      throw new NotFoundException({ code: 'NOT_FOUND', message: 'Cannot POST /v1/auth/dev-token' });
    }

    this.logger.warn(`Minting a DEVELOPMENT session for ${body.email} — no Google verification`);
    return this.auth.establishSession({
      googleSub: `dev:${body.email}`,
      email: body.email,
      name: body.name,
      photoUrl: null,
      timezone: body.timezone,
    });
  }
}
