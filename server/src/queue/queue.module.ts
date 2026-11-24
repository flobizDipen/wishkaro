import { Global, Inject, Module, OnApplicationShutdown } from '@nestjs/common';
import IORedis from 'ioredis';
import { APP_ENV, type AppEnv } from '../config/env';
import { REDIS_CONNECTION } from './queue.constants';

@Global()
@Module({
  providers: [
    {
      provide: REDIS_CONNECTION,
      inject: [APP_ENV],
      useFactory: (env: AppEnv): IORedis =>
        new IORedis(env.REDIS_URL, {
          // BullMQ requires this; without it blocking commands throw at runtime.
          maxRetriesPerRequest: null,
          enableReadyCheck: false,
        }),
    },
  ],
  exports: [REDIS_CONNECTION],
})
export class QueueModule implements OnApplicationShutdown {
  constructor(@Inject(REDIS_CONNECTION) private readonly redis: IORedis) {}

  async onApplicationShutdown(): Promise<void> {
    await this.redis.quit();
  }
}
