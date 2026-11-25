import { createParamDecorator, ExecutionContext } from '@nestjs/common';
import type { Request } from 'express';
import type { AccessTokenClaims } from './auth.service';

export interface AuthenticatedRequest extends Request {
  user?: AccessTokenClaims;
}

/** The authenticated user's id. Present on every non-@Public route by construction. */
export const CurrentUserId = createParamDecorator((_data: unknown, ctx: ExecutionContext): string => {
  const request = ctx.switchToHttp().getRequest<AuthenticatedRequest>();
  const userId = request.user?.sub;
  if (!userId) throw new Error('CurrentUserId used on a route without JwtAuthGuard');
  return userId;
});
