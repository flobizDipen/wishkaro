import { Body, Controller, HttpException, HttpStatus, Module, Post } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { configureApp } from './configure-app';

/**
 * The transport layer, tested at the seam rather than at the constant.
 *
 * What this is guarding is not "the limit is 2mb" — that would pass by reading the same
 * literal twice. It is the behaviour the sync client's chunking depends on
 * (docs/M3_PLAN.md §1):
 *
 *   - a legal 500-row push is not rejected by the TRANSPORT before validation sees it;
 *   - a body that really is too large says so, with a 4xx, rather than arriving as a 500.
 *
 * The second one matters because the client aborts a run on a 5xx and keeps going on a 4xx.
 * Before this, an oversized push was a 500 with the message "Internal server error" and the
 * outbox retried it for ever.
 */

@Controller('echo')
class EchoController {
  @Post()
  echo(@Body() body: { rows?: unknown[] }): { count: number } {
    return { count: body.rows?.length ?? 0 };
  }
}

/** Throws the exact shape M5's rate limiter throws (FR-WSH-08). */
@Controller('limited')
class LimitedController {
  @Post()
  limited(@Body() body: { seconds?: unknown }): never {
    throw new HttpException(
      { code: 'RATE_LIMITED', message: 'Give it a few minutes.', retryAfterSeconds: body.seconds },
      HttpStatus.TOO_MANY_REQUESTS,
    );
  }
}

@Module({ controllers: [EchoController, LimitedController] })
class EchoModule {}

async function appUnderTest(): Promise<NestExpressApplication> {
  const app = await NestFactory.create<NestExpressApplication>(EchoModule, { logger: false });
  configureApp(app);
  await app.init();
  return app;
}

/** A person-shaped row at roughly its legal maximum: `notes` alone allows 2,000 chars. */
const fatRows = (n: number): { rows: unknown[] } => ({
  rows: Array.from({ length: n }, (_, i) => ({
    id: `00000000-0000-7000-8000-${String(i).padStart(12, '0')}`,
    displayName: 'A'.repeat(120),
    notes: 'n'.repeat(2000),
  })),
});

describe('the HTTP layer main.ts installs', () => {
  let app: NestExpressApplication;

  beforeAll(async () => {
    app = await appUnderTest();
  });

  afterAll(async () => {
    await app.close();
  });

  it('accepts a push at the documented 500-row cap, at its worst-case size', async () => {
    // ~1.1 MB. Under body-parser's unconfigured 100 KB default this was a 500 — which is
    // the whole bug: the undocumented transport limit bound before the documented row cap.
    const res = await request(app.getHttpServer()).post('/echo').send(fatRows(500));

    expect(res.status).toBe(201);
    expect(res.body).toEqual({ count: 500 });
  });

  it('accepts the smaller page the client actually sends', async () => {
    const res = await request(app.getHttpServer()).post('/echo').send(fatRows(200));

    expect(res.status).toBe(201);
    expect(res.body).toEqual({ count: 200 });
  });

  it('answers an oversized body with 413 and a reason, not a bare 500', async () => {
    // Over 2 MB. body-parser throws a PayloadTooLargeError, which is an http-errors error
    // and NOT a Nest HttpException — so without the mapping in AllExceptionsFilter it
    // reaches the catch-all and becomes `{"code":"INTERNAL"}`, indistinguishable from the
    // server being broken, and the client aborts the run and retries for ever.
    const res = await request(app.getHttpServer()).post('/echo').send(fatRows(1200));

    expect(res.status).toBe(413);
    expect(res.body.error.code).toBe('PAYLOAD_TOO_LARGE');
    expect(res.body.error.message).toContain('2mb');
  });

  it('answers malformed JSON with 400, not a bare 500', async () => {
    // Nest's Express adapter wraps a JSON syntax error as a BadRequestException before the
    // filter sees it, so this one needs no mapping of our own. Asserted rather than assumed,
    // because that is the only thing keeping an `entity.parse.failed` branch out of the
    // filter as dead code.
    const res = await request(app.getHttpServer())
      .post('/echo')
      .set('Content-Type', 'application/json')
      .send('{"rows": [');

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('BAD_REQUEST');
  });
});

/**
 * `Retry-After`, found by curl rather than by a test.
 *
 * M5's limiter computes the seconds until its window rolls and `toErrorBody` keeps only
 * `code`, `message` and `details` — so the number was computed and dropped, which is the
 * first shape MILESTONE_CHECKLIST.md says to look for. The unit test above it asserted on
 * the thrown exception and therefore never saw the wire; only a real request did.
 */
describe('Retry-After on a 429', () => {
  let app: NestExpressApplication;

  beforeAll(async () => {
    app = await appUnderTest();
  });

  afterAll(async () => {
    await app.close();
  });

  it('reaches the client as a header', async () => {
    const response = await request(app.getHttpServer()).post('/limited').send({ seconds: 1847 });

    expect(response.status).toBe(429);
    expect(response.headers['retry-after']).toBe('1847');
    // And the body keeps its one shape: no fourth key leaking into every error response.
    expect(response.body).toEqual({
      error: { code: 'RATE_LIMITED', message: 'Give it a few minutes.' },
    });
  });

  it('rounds a fractional value up rather than truncating it', async () => {
    // Truncating would tell a client to retry a moment before the window actually rolls,
    // which is a second 429 for a client that did exactly what it was told.
    const response = await request(app.getHttpServer()).post('/limited').send({ seconds: 0.2 });
    expect(response.headers['retry-after']).toBe('1');
  });

  it('is absent when the thrower did not say', async () => {
    // Every other error in the app throws without one; none of them should grow a header.
    const response = await request(app.getHttpServer()).post('/limited').send({});
    expect(response.headers['retry-after']).toBeUndefined();
  });

  it('ignores a value that is not a sane number of seconds', async () => {
    for (const seconds of ['soon', -5, Number.NaN]) {
      const response = await request(app.getHttpServer()).post('/limited').send({ seconds });
      expect(response.headers['retry-after']).toBeUndefined();
    }
  });
});
