import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import { IMAGE_BODY_LIMIT, JSON_BODY_LIMIT } from '../http/limits';

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

      /**
       * `Retry-After`, when the thrower worked out how long to wait.
       *
       * M5's rate limiter (FR-WSH-08) computes the seconds until its window rolls, and
       * `toErrorBody` keeps only `code`, `message` and `details` — so without this the number
       * was computed and then dropped on the floor. That is the first shape
       * MILESTONE_CHECKLIST.md tells you to look for, and this one was found by running a
       * curl rather than by a test, because the test asserted on the thrown exception and
       * never saw the wire.
       *
       * The header rather than the body: it is what HTTP has for exactly this, a client can
       * act on it without parsing anything, and `details` already means "which row failed
       * validation" to the sync client — putting a number there would make it try to
       * attribute the number to a row.
       */
      const retryAfter = retryAfterSecondsOf(payload);
      if (retryAfter !== null) response.setHeader('Retry-After', String(retryAfter));

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
    const bodyParser = asBodyParserError(exception, request.headers['content-type']);
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
    // Overwritten per request by [limitFor] — M6 added a second parser with a different
    // ceiling and both set the same `err.type`, so a fixed message names the wrong limit
    // for one of them.
    message: `Request body is larger than the ${JSON_BODY_LIMIT} limit`,
  },
};

/**
 * Which limit a 413 should quote.
 *
 * Both body parsers throw `entity.too.large` and nothing on the error distinguishes them,
 * so this keys on what the request was. Without it a 9 MB photo — routine from a 48 MP
 * handset — was told "larger than the 2mb limit" when 8 MB would have worked, sending the
 * user (or a developer) to the wrong constant.
 */
function limitFor(contentType: string | undefined): string {
  return contentType?.startsWith('image/') ? IMAGE_BODY_LIMIT : JSON_BODY_LIMIT;
}

function asBodyParserError(
  exception: unknown,
  contentType?: string,
): { status: number; code: string; message: string; type: string } | null {
  if (!exception || typeof exception !== 'object') return null;
  const type = (exception as { type?: unknown }).type;
  if (typeof type !== 'string') return null;
  const known = BODY_PARSER_ERRORS[type];
  if (!known) return null;
  if (type === 'entity.too.large') {
    return {
      ...known,
      type,
      message: `Request body is larger than the ${limitFor(contentType)} limit`,
    };
  }
  return { ...known, type };
}

/** A whole, non-negative number of seconds from the thrown payload, or null. */
function retryAfterSecondsOf(payload: unknown): number | null {
  if (!payload || typeof payload !== 'object') return null;
  const value = (payload as { retryAfterSeconds?: unknown }).retryAfterSeconds;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null;
  return Math.ceil(value);
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
