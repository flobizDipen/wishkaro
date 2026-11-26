import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import { JSON_BODY_LIMIT } from '../http/limits';

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

    /**
     * body-parser throws before any controller runs, and its errors come from `http-errors`,
     * not from Nest — so they are not `HttpException`s and used to fall through to the 500
     * below. A request that was too large, or that carried malformed JSON, therefore looked
     * to the client exactly like the server being broken.
     *
     * That distinction is load-bearing for the sync client: it aborts a run on a 5xx and
     * keeps going on a 4xx (docs/M3_PLAN.md §1.6). A 413 wearing a 500's clothes makes an
     * oversized push retry for ever instead of being attributed and reported.
     *
     * Only these two types are translated. Anything else with a `status` on it stays an
     * INTERNAL, because a stack-carrying error's message is not something to put on the wire.
     */
    const bodyParser = asBodyParserError(exception);
    if (bodyParser) {
      this.logger.warn(
        `Rejected ${request.method} ${request.url}: ${bodyParser.code} (${bodyParser.type})`,
      );
      response.status(bodyParser.status).json({
        error: { code: bodyParser.code, message: bodyParser.message },
      } satisfies ErrorBody);
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

/**
 * The body-parser failures worth telling a client apart from a server fault.
 *
 * Only one of them. Malformed JSON (`entity.parse.failed`) is NOT here, because Nest's
 * Express adapter already wraps a JSON syntax error as a `BadRequestException` and it
 * therefore never reaches this far — verified in configure-app.spec.ts, which asserts the
 * 400 rather than taking it on trust. An entry for it would be dead code.
 */
const BODY_PARSER_ERRORS: Record<string, { status: number; code: string; message: string }> = {
  'entity.too.large': {
    status: HttpStatus.PAYLOAD_TOO_LARGE,
    code: 'PAYLOAD_TOO_LARGE',
    message: `Request body is larger than the ${JSON_BODY_LIMIT} limit`,
  },
};

function asBodyParserError(
  exception: unknown,
): { status: number; code: string; message: string; type: string } | null {
  if (!exception || typeof exception !== 'object') return null;
  const type = (exception as { type?: unknown }).type;
  if (typeof type !== 'string') return null;
  const known = BODY_PARSER_ERRORS[type];
  return known ? { ...known, type } : null;
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
