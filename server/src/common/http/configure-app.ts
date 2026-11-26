import type { NestExpressApplication } from '@nestjs/platform-express';
import { AllExceptionsFilter } from '../filters/http-exception.filter';
import { JSON_BODY_LIMIT } from './limits';

/**
 * Everything about the HTTP layer that both `main.ts` and the tests need to agree on.
 *
 * It exists so the body limit is configured in exactly one place. A limit set inside
 * `main.ts` is a limit no test can reach, and this one has already been wrong once by being
 * implicit.
 */
export function configureApp(app: NestExpressApplication): void {
  app.useBodyParser('json', { limit: JSON_BODY_LIMIT });
  app.useGlobalFilters(new AllExceptionsFilter());
}
