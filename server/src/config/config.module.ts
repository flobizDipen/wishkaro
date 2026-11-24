import { Global, Module } from '@nestjs/common';
import { APP_ENV, AppEnv, parseEnv } from './env';

@Global()
@Module({
  providers: [{ provide: APP_ENV, useFactory: (): AppEnv => parseEnv() }],
  exports: [APP_ENV],
})
export class AppConfigModule {}
