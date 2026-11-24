import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import type { Request, Response } from 'express';

interface ErrorBody {
  error: { code: string; message: string; details?: unknown };
}

/**
 * One error shape for every endpoint: `{ error: { code, message, details? } }`.
 *
 * Unexpected errors are logged in full server-side and reduced to INTERNAL on the wire —
 * a stack trace in a response body is a gift to whoever is probing a public API.
 */
@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  private readonly logger = new Logger('ExceptionFilter');

  catch(exception: unknown, host: ArgumentsHost): void {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<Response>();
    const request = ctx.getRequest<Request>();

    if (exception instanceof HttpException) {
      const status = exception.getStatus();
      const payload = exception.getResponse();
      response.status(status).json(toErrorBody(status, payload));
      return;
    }

    this.logger.error(
      `Unhandled error on ${request.method} ${request.url}`,
      exception instanceof Error ? exception.stack : String(exception),
    );
    response.status(HttpStatus.INTERNAL_SERVER_ERROR).json({
      error: { code: 'INTERNAL', message: 'Internal server error' },
    } satisfies ErrorBody);
  }
}

function toErrorBody(status: number, payload: unknown): ErrorBody {
  if (typeof payload === 'string') {
    return { error: { code: defaultCode(status), message: payload } };
  }
  if (payload && typeof payload === 'object') {
    const p = payload as Record<string, unknown>;
    if (typeof p.code === 'string' && typeof p.message === 'string') {
      return { error: { code: p.code, message: p.message, details: p.details } };
    }
    const message = typeof p.message === 'string' ? p.message : defaultCode(status);
    return { error: { code: defaultCode(status), message } };
  }
  return { error: { code: defaultCode(status), message: defaultCode(status) } };
}

function defaultCode(status: number): string {
  switch (status) {
    case HttpStatus.BAD_REQUEST:
      return 'BAD_REQUEST';
    case HttpStatus.UNAUTHORIZED:
      return 'UNAUTHORIZED';
    case HttpStatus.FORBIDDEN:
      return 'FORBIDDEN';
    case HttpStatus.NOT_FOUND:
      return 'NOT_FOUND';
    case HttpStatus.CONFLICT:
      return 'CONFLICT';
    default:
      return 'ERROR';
  }
}
