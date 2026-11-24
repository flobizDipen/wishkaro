import { ArgumentMetadata, BadRequestException, PipeTransform } from '@nestjs/common';
import type { ZodType } from 'zod';

/**
 * Validates one argument against a Zod schema and returns the parsed value, so handlers
 * receive data that is typed AND true. Failures become a typed 400 rather than a stack trace.
 *
 * Usage: `@Body(new ZodValidationPipe(CreatePersonSchema)) body: CreatePerson`
 */
export class ZodValidationPipe<T> implements PipeTransform<unknown, T> {
  constructor(private readonly schema: ZodType<T>) {}

  transform(value: unknown, _metadata: ArgumentMetadata): T {
    const result = this.schema.safeParse(value);
    if (result.success) return result.data;

    throw new BadRequestException({
      code: 'VALIDATION_FAILED',
      message: 'Request body failed validation',
      details: result.error.issues.map((issue) => ({
        path: issue.path.join('.'),
        message: issue.message,
        code: issue.code,
      })),
    });
  }
}
