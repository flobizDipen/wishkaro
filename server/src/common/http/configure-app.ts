import { raw, type NextFunction, type Request, type Response } from 'express';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { AllExceptionsFilter } from '../filters/http-exception.filter';
import { IMAGE_BODY_LIMIT, JSON_BODY_LIMIT } from './limits';

/**
 * Everything about the HTTP layer that both `main.ts` and the tests need to agree on.
 *
 * It exists so the body limit is configured in exactly one place. A limit set inside
 * `main.ts` is a limit no test can reach, and this one has already been wrong once by being
 * implicit.
 */
/** `POST /v1/people/:personId/photo`, and nothing else. */
const PHOTO_UPLOAD_PATH = /^\/v1\/people\/[^/]+\/photo$/;

export function configureApp(app: NestExpressApplication): void {
  app.useBodyParser('json', { limit: JSON_BODY_LIMIT });
  /**
   * Raw image bodies for the photo upload (M6), as a Buffer.
   *
   * Multipart would be the conventional choice and is deliberately not used: it would pull
   * in a parser, a temp-file strategy and a disk path for one endpoint that takes exactly
   * one file and no fields. A raw body with a content type is the whole request.
   *
   * The `type` list is what makes this safe to register globally — anything that is not one
   * of these three content types is not parsed as a Buffer and falls through to the JSON
   * parser, so no existing route changes behaviour.
   */
  /**
   * Raw image bodies, scoped to the ONE route that wants them.
   *
   * This was `app.useBodyParser('raw', ...)`, which registers GLOBAL Express middleware —
   * and body parsers run before Nest's `APP_GUARD`. The comment here claimed "no existing
   * route changes behaviour", which was wrong in the way that matters: it was true for
   * other content types and false for the limit. Measured against the running server:
   *
   *     POST /v1/auth/google, Content-Type: image/png   -> 3,000,000 bytes read, then 400
   *     POST /v1/auth/google, Content-Type: application/json -> cut off at 2 MB, 413
   *
   * So any unauthenticated caller could make the server buffer up to `IMAGE_BODY_LIMIT`
   * on ANY route, including the auth routes, simply by setting a content type — raising
   * the pre-auth ceiling from 2 MB to 8 MB app-wide.
   *
   * Scoped by path, so only the photo upload can ever read an 8 MB body.
   */
  const rawImage = raw({
    limit: IMAGE_BODY_LIMIT,
    type: ['image/jpeg', 'image/png', 'image/webp'],
  });
  app.use((req: Request, res: Response, next: NextFunction) => {
    if (req.method === 'POST' && PHOTO_UPLOAD_PATH.test(req.path)) {
      rawImage(req, res, next);
      return;
    }
    next();
  });
  app.useGlobalFilters(new AllExceptionsFilter());
}
