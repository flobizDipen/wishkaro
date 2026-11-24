import { Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { LoggerModule } from 'nestjs-pino';
import { AppConfigModule } from './config/config.module';
import { PrismaModule } from './prisma/prisma.module';
import { FirebaseModule } from './firebase/firebase.module';
import { QueueModule } from './queue/queue.module';
import { HealthController } from './health/health.controller';
import { AuthModule } from './modules/auth/auth.module';
import { JwtAuthGuard } from './modules/auth/jwt-auth.guard';
import { DevicesModule } from './modules/devices/devices.module';
import { EventsModule } from './modules/events/events.module';
import { PeopleModule } from './modules/people/people.module';
import { RemindersModule } from './modules/reminders/reminders.module';
import { SyncModule } from './modules/sync/sync.module';

@Module({
  imports: [
    AppConfigModule,
    LoggerModule.forRoot({
      pinoHttp: {
        level: process.env.NODE_ENV === 'production' ? 'info' : 'debug',
        transport: process.env.NODE_ENV === 'production' ? undefined : { target: 'pino-pretty' },
        // No PII in logs (REQUIREMENTS.md §11). Tokens and bodies never reach the log line.
        redact: ['req.headers.authorization', 'req.headers.cookie'],
        autoLogging: { ignore: (req) => req.url === '/health' },
      },
    }),
    PrismaModule,
    FirebaseModule,
    QueueModule,
    AuthModule,
    PeopleModule,
    EventsModule,
    DevicesModule,
    RemindersModule,
    SyncModule,
  ],
  controllers: [HealthController],
  providers: [{ provide: APP_GUARD, useClass: JwtAuthGuard }],
})
export class AppModule {}
