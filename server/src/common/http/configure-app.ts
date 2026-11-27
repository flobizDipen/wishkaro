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
  app.useBodyParser('raw', {
    limit: IMAGE_BODY_LIMIT,
    type: ['image/jpeg', 'image/png', 'image/webp'],
  });
  app.useGlobalFilters(new AllExceptionsFilter());
}
