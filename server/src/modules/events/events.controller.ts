import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Patch,
  Post,
  Put,
  Query,
} from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RemindersScheduler } from '../reminders/reminders.scheduler';
import { EventsService, type UpcomingEventView } from './events.service';
import {
  EventDateSchema,
  EventTypeSchema,
  refineEventDate,
  refineEventLabel,
  UuidSchema,
} from '../sync/sync.schemas';
import { assertValidSpec, InvalidEventDateError } from '../../common/date/occurrence';

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
    type: EventTypeSchema.default('BIRTHDAY'),
    ...EventDateSchema.shape,
    label: z.string().trim().min(1).max(60).nullable().optional(),
  })
  .strict()
  .superRefine(refineEventDate)
  .superRefine(refineEventLabel);

/**
 * A PATCH body. Every field optional; `sentiment` and `milestoneLabel` are absent because
 * both are derived, and `.strict()` turns an attempt to send one into a 400 that names it.
 *
 * The date fields are validated together rather than individually: changing only `day` from
 * 28 to 31 on a February event has to fail, and a per-field check cannot see that.
 */
const UpdateEventSchema = z
  .object({
    type: EventTypeSchema.optional(),
    day: z.number().int().min(1).max(31).optional(),
    month: z.number().int().min(1).max(12).optional(),
    year: z.number().int().min(1900).max(2200).nullable().optional(),
    label: z.string().trim().min(1).max(60).nullable().optional(),
    isMuted: z.boolean().optional(),
  })
  .strict()
  .refine((b) => Object.keys(b).length > 0, { message: 'nothing to update' });

const OverrideSchema = z
  .object({
    /** null clears the override; the following year reverts to the base date anyway. */
    overrideDate: IsoDate.nullable(),
  })
  .strict();

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
    return this.events.upcoming(userId, {
      ...(query.from && { from: query.from }),
      ...(query.to && { to: query.to }),
    });
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

  @Patch(':id')
  async update(
    @CurrentUserId() userId: string,
    @Param('id') id: string,
    @Body(new ZodValidationPipe(UpdateEventSchema)) body: z.infer<typeof UpdateEventSchema>,
  ): Promise<{ id: string }> {
    const updated = await this.events.update(userId, id, body);
    await this.scheduler.materialiseNow(userId);
    return updated;
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@CurrentUserId() userId: string, @Param('id') id: string): Promise<void> {
    await this.events.softDelete(userId, id);
  }

  @Post(':id/restore')
  async restore(@CurrentUserId() userId: string, @Param('id') id: string): Promise<{ id: string }> {
    const restored = await this.events.restore(userId, id);
    await this.scheduler.materialiseNow(userId);
    return restored;
  }

  /**
   * Per-occurrence date override (FR-EVT-10) — "observing on a different date this year".
   *
   * PUT rather than PATCH: the body replaces the override outright, and sending null is how
   * you remove it. A PATCH with an absent field would be ambiguous between "leave it" and
   * "clear it", which is precisely the distinction this endpoint exists to express.
   */
  @Put(':id/occurrences/:year/override')
  async setOverride(
    @CurrentUserId() userId: string,
    @Param('id') id: string,
    @Param('year') year: string,
    @Body(new ZodValidationPipe(OverrideSchema)) body: z.infer<typeof OverrideSchema>,
  ): Promise<{ eventId: string; occurrenceYear: number; overrideDate: string | null }> {
    const occurrenceYear = Number(year);
    if (!Number.isInteger(occurrenceYear)) {
      throw new BadRequestException({
        code: 'INVALID_OCCURRENCE_YEAR',
        message: `occurrence year must be an integer, got ${year}`,
      });
    }

    // A date that does not exist must not reach the scheduler, which would then throw on
    // every tick for every user. `assertValidSpec` throws InvalidEventDateError, which is a
    // plain Error — letting it escape a controller turns a user's typo into a 500.
    if (body.overrideDate) {
      try {
        assertValidSpec(body.overrideDate);
      } catch (cause) {
        throw new BadRequestException({
          code: 'INVALID_OVERRIDE_DATE',
          message: cause instanceof InvalidEventDateError ? cause.message : 'invalid override date',
        });
      }
    }

    const result = await this.events.setOccurrenceOverride(
      userId,
      id,
      occurrenceYear,
      body.overrideDate,
    );
    await this.scheduler.materialiseNow(userId);
    return result;
  }
}
