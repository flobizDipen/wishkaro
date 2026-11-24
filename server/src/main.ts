import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { Logger as PinoLogger } from 'nestjs-pino';
import { AppModule } from './app.module';
import { AllExceptionsFilter } from './common/filters/http-exception.filter';
import { APP_ENV, loadDotEnv, parseEnv, type AppEnv } from './config/env';

async function bootstrap(): Promise<void> {
  loadDotEnv();
  // Parse before Nest starts, so a configuration mistake is a clear message at line one
  // rather than a dependency-injection stack trace thirty lines down.
  const env = parseEnv();

  const app = await NestFactory.create(AppModule, { bufferLogs: true });
  app.useLogger(app.get(PinoLogger));
  app.useGlobalFilters(new AllExceptionsFilter());
  app.enableShutdownHooks();

  const resolved = app.get<AppEnv>(APP_ENV);
  await app.listen(resolved.PORT, '0.0.0.0');

  const logger = app.get(PinoLogger);
  logger.log(`WishKaro API listening on :${resolved.PORT} (${resolved.NODE_ENV})`);
  if (env.ALLOW_DEV_LOGIN) {
    logger.warn('ALLOW_DEV_LOGIN is on — POST /v1/auth/dev-token mints sessions without Google');
  }
}

void bootstrap().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
