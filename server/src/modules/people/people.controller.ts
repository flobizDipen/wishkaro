import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Patch,
  Post,
  Query,
} from '@nestjs/common';
import { z } from 'zod';
import { ZodValidationPipe } from '../../common/zod/zod-validation.pipe';
import { CurrentUserId } from '../auth/current-user.decorator';
import { RemindersScheduler } from '../reminders/reminders.scheduler';
import { CURATED_RELATIONS, PeopleService, type PersonView } from './people.service';
import {
  EventDateSchema,
  EventTypeSchema,
  refineEventDate,
  refineEventLabel,
  UuidSchema,
} from '../sync/sync.schemas';

/**
 * FR-PPL-01: a person needs exactly three inputs — name, event date, relation. Everything
 * else is optional and arrives later through PATCH, which is why none of it is accepted here.
 *
 * `sentiment`, `isMinor` and `completenessScore` are absent on purpose: all three are derived
 * server-side, and the schema is `.strict()`, so sending one is a 400 rather than a silent
 * strip (docs/BUILD_PROMPT_M1.md).
 */
const CreatePersonSchema = z
  .object({
    id: UuidSchema.optional(),
    displayName: z.string().trim().min(1).max(120),
    relationToOwner: z.string().trim().min(1).max(60).optional(),
    event: z
      .object({
        id: UuidSchema.optional(),
        type: EventTypeSchema.default('BIRTHDAY'),
        ...EventDateSchema.shape,
        label: z.string().trim().min(1).max(60).nullable().optional(),
      })
      .strict()
      // Without this, 31 April passes the per-field ranges, reaches the database, and then
      // throws on the first read that tries to compute its next occurrence.
      .superRefine(refineEventDate)
      .superRefine(refineEventLabel)
      .optional(),
  })
  .strict();

/** A PATCH body. Absent means "leave alone"; explicit null means "clear". */
const UpdatePersonSchema = z
  .object({
    displayName: z.string().trim().min(1).max(120).optional(),
    relationToOwner: z.string().trim().max(60).nullable().optional(),
    gender: z.string().trim().max(40).nullable().optional(),
    phone: z.string().trim().max(40).nullable().optional(),
    email: z.string().trim().max(254).nullable().optional(),
    notes: z.string().trim().max(2000).nullable().optional(),
    isDeceased: z.boolean().optional(),
  })
  .strict()
  .refine((b) => Object.keys(b).length > 0, { message: 'nothing to update' });

const SearchQuerySchema = z
  .object({
    q: z.string().trim().min(1).max(120).optional(),
    relation: z.string().trim().min(1).max(60).optional(),
    month: z.coerce.number().int().min(1).max(12).optional(),
    maxCompleteness: z.coerce.number().int().min(0).max(100).optional(),
  })
  .strict();

const DuplicateQuerySchema = z
  .object({
    displayName: z.string().trim().min(1).max(120),
    day: z.coerce.number().int().min(1).max(31).optional(),
    month: z.coerce.number().int().min(1).max(12).optional(),
  })
  .strict();

const AddTraitSchema = z
  .object({
    id: UuidSchema.optional(),
    kind: z.enum(['HOBBY', 'LIKE', 'DISLIKE', 'BUDGET_BAND']),
    value: z.string().trim().min(1).max(120),
    // `source` is deliberately not accepted: M1 only writes USER, and letting a client claim
    // INFERRED would launder a guess into something the UI shows as fact.
  })
  .strict();

@Controller('v1/people')
export class PeopleController {
  constructor(
    private readonly people: PeopleService,
    private readonly scheduler: RemindersScheduler,
  ) {}

  // NOTE: the literal routes below must stay above `:id`, or Nest matches "relations" and
  // "deleted" as person ids and every request 404s with a confusing message.

  /** The curated chip list (FR-PPL-02), served so the client and server cannot drift. */
  @Get('relations')
  relations(): { relations: readonly string[] } {
    return { relations: CURATED_RELATIONS };
  }

  /** People inside the 30-day restore window (FR-PPL-08). */
  @Get('deleted')
  listDeleted(@CurrentUserId() userId: string): Promise<PersonView[]> {
    return this.people.listDeleted(userId);
  }

  /** Create-time duplicate check (FR-PPL-06). Import-time dedup is M3. */
  @Get('duplicates')
  duplicates(
    @CurrentUserId() userId: string,
    @Query(new ZodValidationPipe(DuplicateQuerySchema)) query: z.infer<typeof DuplicateQuerySchema>,
  ): Promise<PersonView[]> {
    const date = query.day && query.month ? { day: query.day, month: query.month } : null;
    return this.people.findDuplicates(userId, query.displayName, date);
  }

  /** Search and filter by name, relation, month and completeness (FR-PPL-10). */
  @Get()
  list(
    @CurrentUserId() userId: string,
    @Query(new ZodValidationPipe(SearchQuerySchema)) query: z.infer<typeof SearchQuerySchema>,
  ): Promise<PersonView[]> {
    return this.people.list(userId, query);
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

  @Patch(':id')
  async update(
    @CurrentUserId() userId: string,
    @Param('id') id: string,
    @Body(new ZodValidationPipe(UpdatePersonSchema)) body: z.infer<typeof UpdatePersonSchema>,
  ): Promise<PersonView> {
    const person = await this.people.update(userId, id, body);
    // Marking someone deceased mutes their birthday, which changes what is scheduled.
    await this.scheduler.materialiseNow(userId);
    return person;
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@CurrentUserId() userId: string, @Param('id') id: string): Promise<void> {
    await this.people.softDelete(userId, id);
  }

  @Post(':id/restore')
  async restore(@CurrentUserId() userId: string, @Param('id') id: string): Promise<PersonView> {
    const person = await this.people.restore(userId, id);
    await this.scheduler.materialiseNow(userId);
    return person;
  }

  @Post(':id/traits')
  @HttpCode(HttpStatus.CREATED)
  addTrait(
    @CurrentUserId() userId: string,
    @Param('id') id: string,
    @Body(new ZodValidationPipe(AddTraitSchema)) body: z.infer<typeof AddTraitSchema>,
  ): Promise<PersonView> {
    return this.people.addTrait(userId, id, body);
  }

  @Delete(':id/traits/:traitId')
  removeTrait(
    @CurrentUserId() userId: string,
    @Param('id') id: string,
    @Param('traitId') traitId: string,
  ): Promise<PersonView> {
    return this.people.removeTrait(userId, id, traitId);
  }
}
