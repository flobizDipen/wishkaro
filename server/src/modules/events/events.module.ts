import { Module } from '@nestjs/common';
import { RemindersModule } from '../reminders/reminders.module';
import { EventsController } from './events.controller';
import { EventsService } from './events.service';

@Module({
  imports: [RemindersModule],
  controllers: [EventsController],
  providers: [EventsService],
  exports: [EventsService],
})
export class EventsModule {}
