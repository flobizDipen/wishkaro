import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RemindersScheduler } from '../reminders/reminders.scheduler';
import { PeopleService, type PersonView } from './people.service';
import { EventDateSchema, refineEventDate, UuidSchema } from '../sync/sync.schemas';

const CreatePersonSchema = z.object({
  id: UuidSchema.optional(),
  displayName: z.string().trim().min(1).max(120),
  relationToOwner: z.string().trim().min(1).max(60).optional(),
  /**
   * M0 creates exactly one event per person, and only a birthday. `sentiment` is absent on
   * purpose — it is derived server-side and never accepted from a client (FR-EVT-01a).
   */
  event: z
    .object({
      id: UuidSchema.optional(),
      type: z.literal('BIRTHDAY').default('BIRTHDAY'),
      ...EventDateSchema.shape,
    })
    // Without this, 31 April passes the per-field ranges, reaches the database, and then
    // throws on the first read that tries to compute its next occurrence.
    .superRefine(refineEventDate)
    .optional(),
});

@Controller('v1/people')
export class PeopleController {
  constructor(
    private readonly people: PeopleService,
    private readonly scheduler: RemindersScheduler,
  ) {}

  @Get()
  list(@CurrentUserId() userId: string): Promise<PersonView[]> {
    return this.people.list(userId);
  }

  @Get(':id')
  get(@CurrentUserId() userId: string, @Param('id') id: string): Promise<PersonView> {
    return this.people.get(userId, id);
  }

  @Post()
  @HttpCode(HttpStatus.CREATED)
  async create(
    @CurrentUserId() userId: string,
    @Body(new ZodValidationPipe(CreatePersonSchema)) body: z.infer<typeof CreatePersonSchema>,
  ): Promise<PersonView> {
    const person = await this.people.create(userId, body);
    // Schedule immediately rather than up to ten minutes from now: adding a birthday and
    // seeing nothing happen is indistinguishable from a broken scheduler.
    await this.scheduler.materialiseNow(userId);
    return person;
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@CurrentUserId() userId: string, @Param('id') id: string): Promise<void> {
    await this.people.softDelete(userId, id);
  }
}
