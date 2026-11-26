import { Body, Controller, Module, Post } from '@nestjs/common';
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

@Module({ controllers: [EchoController] })
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
