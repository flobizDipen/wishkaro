import { Module } from '@nestjs/common';
import { RemindersModule } from '../reminders/reminders.module';
import { SyncController } from './sync.controller';
import { SyncService } from './sync.service';

@Module({
  imports: [RemindersModule],
  controllers: [SyncController],
  providers: [SyncService],
  exports: [SyncService],
})
export class SyncModule {}
