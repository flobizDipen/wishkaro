import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Query } from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RemindersScheduler } from '../reminders/reminders.scheduler';
import { EventsService, type UpcomingEventView } from './events.service';
import { EventDateSchema, refineEventDate, UuidSchema } from '../sync/sync.schemas';

const IsoDate = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, 'expected YYYY-MM-DD')
  .transform((s) => {
    const [year, month, day] = s.split('-').map(Number) as [number, number, number];
    return { year, month, day };
  });

const UpcomingQuerySchema = z.object({ from: IsoDate.optional(), to: IsoDate.optional() });

const CreateEventSchema = z
  .object({
    id: UuidSchema.optional(),
    personId: UuidSchema,
    type: z.literal('BIRTHDAY').default('BIRTHDAY'),
    ...EventDateSchema.shape,
  })
  .superRefine(refineEventDate);

@Controller('v1/events')
export class EventsController {
  constructor(
    private readonly events: EventsService,
    private readonly scheduler: RemindersScheduler,
  ) {}

  @Get('upcoming')
  upcoming(
    @CurrentUserId() userId: string,
    @Query(new ZodValidationPipe(UpcomingQuerySchema)) query: z.infer<typeof UpcomingQuerySchema>,
  ): Promise<UpcomingEventView[]> {
    return this.events.upcoming(userId, { ...(query.from && { from: query.from }), ...(query.to && { to: query.to }) });
  }

  @Post()
  @HttpCode(HttpStatus.CREATED)
  async create(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(CreateEventSchema)) body: z.infer<typeof CreateEventSchema>,
  ): Promise<{ id: string }> {
    const created = await this.events.create(userId, body);
    await this.scheduler.materialiseNow(userId);
    return created;
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@CurrentUserId() userId: string, @Param('id') id: string): Promise<void> {
    await this.events.softDelete(userId, id);
  }
}
