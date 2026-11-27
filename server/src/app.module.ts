import { Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { LoggerModule } from 'nestjs-pino';
import { AppConfigModule } from './config/config.module';
import { PrismaModule } from './prisma/prisma.module';
import { DerivationModule } from './modules/people/derivation.module';
import { FirebaseModule } from './firebase/firebase.module';
import { QueueModule } from './queue/queue.module';
import { HealthController } from './health/health.controller';
import { AccountModule } from './modules/account/account.module';
import { AuthModule } from './modules/auth/auth.module';
import { JwtAuthGuard } from './modules/auth/jwt-auth.guard';
import { DevicesModule } from './modules/devices/devices.module';
import { EventsModule } from './modules/events/events.module';
import { MediaModule } from './modules/media/media.module';
import { ModerationModule } from './modules/moderation/moderation.module';
import { PeopleModule } from './modules/people/people.module';
import { PostersModule } from './modules/posters/posters.module';
import { RemindersModule } from './modules/reminders/reminders.module';
import { RetentionModule } from './modules/retention/retention.module';
import { SettingsModule } from './modules/settings/settings.module';
import { SyncModule } from './modules/sync/sync.module';
import { WishesModule } from './modules/wishes/wishes.module';

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
    DerivationModule,
    FirebaseModule,
    QueueModule,
    MediaModule,
    ModerationModule,
    AuthModule,
    AccountModule,
    PeopleModule,
    EventsModule,
    DevicesModule,
    RemindersModule,
    RetentionModule,
    SettingsModule,
    SyncModule,
    WishesModule,
    PostersModule,
  ],
  controllers: [HealthController],
  providers: [{ provide: APP_GUARD, useClass: JwtAuthGuard }],
})
export class AppModule {}
