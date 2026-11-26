import type OpenAI from 'openai';
import type { Response as OpenAIResponse } from 'openai/resources/responses/responses';
import { OpenAiProvider, parseDraft, readUsage } from './openai.provider';
import { costOf } from './pricing';

/**
 * The OpenAI adapter's mapping, with no network.
 *
 * **What this can and cannot prove.** There is no OpenAI key on the build machine, so
 * nothing in this suite has made a real request. The response objects below are built
 * against the installed SDK's own `Response` type — `tsc` rejects a field that does not
 * exist on it — so they are not invented from memory. But a type is a claim about a shape,
 * not a recording of one, and if the live API ever returns something the types do not
 * describe, every test here still passes. `M5_VERIFY.md` records this as PARTIAL rather
 * than rounding it up to a pass.
 *
 * What it does prove is the part that would be silently wrong rather than loudly broken:
 * the token arithmetic, which decides what the cost ledger says, and the refusal check,
 * which decides what the user sees on the screen where they are writing about a death.
 */

const EMPTY_USAGE = {
  input_tokens: 0,
  output_tokens: 0,
  total_tokens: 0,
  input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
  output_tokens_details: { reasoning_tokens: 0 },
};

function response(over: Partial<OpenAIResponse> = {}): OpenAIResponse {
  return {
    id: 'resp_1',
    object: 'response',
    created_at: 0,
    model: 'gpt-6-luna',
    status: 'completed',
    output: [],
    // Schema-shaped, per §10 rule 3. Plain text here is what the adapter used to send and
    // what `parseDraft` now rejects — these fixtures asserted the old behaviour.
    output_text: '{"message":"Many happy returns."}',
    error: null,
    incomplete_details: null,
    instructions: null,
    metadata: null,
    parallel_tool_calls: false,
    temperature: null,
    tool_choice: 'auto',
    tools: [],
    top_p: null,
    usage: EMPTY_USAGE,
    ...over,
  } as unknown as OpenAIResponse;
}

/** A client that returns one canned response, so `draft()` can be driven end to end. */
function clientReturning(value: OpenAIResponse | Error): OpenAI {
  return {
    responses: {
      create: async () => {
        if (value instanceof Error) throw value;
        return value;
      },
    },
  } as unknown as OpenAI;
}

const provider = (client: OpenAI) =>
  new OpenAiProvider('sk-not-a-real-key', 'gpt-6-luna', 'low', client);
const PROMPT = { system: 'system', user: 'user' };

describe('readUsage — the disjointness contract', () => {
  it('SUBTRACTS the cached and written tokens from the total', () => {
    // THE ONE THAT WOULD CORRUPT THE LEDGER. OpenAI's `input_tokens` is the total with
    // `cached_tokens` and `cache_write_tokens` as subsets; Anthropic's is the remainder
    // after them. Passing these through unchanged bills the cached tokens twice.
    const usage = readUsage({
      usage: {
        ...EMPTY_USAGE,
        input_tokens: 1000,
        output_tokens: 120,
        input_tokens_details: { cached_tokens: 700, cache_write_tokens: 100 },
      },
    } as Pick<OpenAIResponse, 'usage'>);

    expect(usage).toEqual({
      inputTokens: 200,
      outputTokens: 120,
      cacheReadTokens: 700,
      cacheWriteTokens: 100,
    });
    // The four add back up to what OpenAI reported, which is what "disjoint" means.
    expect(usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens).toBe(1000);
  });

  it('is the difference between a right and a wrong bill, in money', () => {
    // Stated as rupees rather than as tokens, because that is the number somebody acts on.
    const correct = readUsage({
      usage: {
        ...EMPTY_USAGE,
        input_tokens: 1000,
        output_tokens: 120,
        input_tokens_details: { cached_tokens: 900, cache_write_tokens: 0 },
      },
    } as Pick<OpenAIResponse, 'usage'>);

    const naive = {
      inputTokens: 1000, // the bug: the total, passed through
      outputTokens: 120,
      cacheReadTokens: 900,
      cacheWriteTokens: 0,
    };

    const right = costOf('gpt-6-luna', correct, 89);
    const wrong = costOf('gpt-6-luna', naive, 89);
    expect(wrong.usd).toBeGreaterThan(right.usd);
    // And it is wrong in the direction that matters: it erases the saving, so a reader
    // concludes caching is not worth having and turns it off.
    expect(wrong.usd / right.usd).toBeGreaterThan(1.3);
  });

  it('clamps rather than going negative when the subsets exceed the total', () => {
    // Not expected. But a negative token count becomes a negative cost, and a ledger that
    // can credit itself is worse than one that is merely wrong.
    const usage = readUsage({
      usage: {
        ...EMPTY_USAGE,
        input_tokens: 10,
        input_tokens_details: { cached_tokens: 50, cache_write_tokens: 0 },
      },
    } as Pick<OpenAIResponse, 'usage'>);
    expect(usage.inputTokens).toBe(0);
  });

  it('returns zeros when usage is missing, rather than throwing', () => {
    // The row still gets written. A gap in the ledger is worse than a zero — and the
    // provider logs a warning, because a zero that nobody notices reads as a free draft.
    expect(readUsage({ usage: undefined } as unknown as Pick<OpenAIResponse, 'usage'>)).toEqual({
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
    });
  });
});

describe('draft — what comes back', () => {
  it('returns the text on a completed response', async () => {
    const result = await provider(clientReturning(response())).draft(PROMPT);
    expect(result.outcome).toBe('OK');
    expect(result.text).toBe('Many happy returns.');
    expect(result.model).toBe('gpt-6-luna');
  });

  it('reports the model the API SAYS served it, not the one we asked for', async () => {
    // The ledger's `model` column has to be what actually ran. There is no server-side
    // fallback on this provider, but an alias resolving to a dated snapshot is routine, and
    // a cost table that records the alias cannot be reconciled against an invoice.
    const result = await provider(
      clientReturning(response({ model: 'gpt-6-luna-2026-11-01' })),
    ).draft(PROMPT);
    expect(result.model).toBe('gpt-6-luna-2026-11-01');
  });

  it('catches a REFUSAL before it reads any text', async () => {
    // The trap. A refusal is a content part sitting where a text part would be, on a
    // response that is otherwise unremarkable — HTTP 200, status completed. Code that
    // collects text and ignores the rest shows an empty composer and no error at all, on
    // the screen where somebody is writing about a dead relative.
    const refused = response({
      output_text: '',
      output: [
        {
          type: 'message',
          id: 'msg_1',
          status: 'completed',
          role: 'assistant',
          content: [{ type: 'refusal', refusal: "I can't help with that." }],
        },
      ],
    } as Partial<OpenAIResponse>);

    const result = await provider(clientReturning(refused)).draft(PROMPT);
    expect(result.outcome).toBe('REFUSED');
    expect(result.text).toBe('');
    // The category reaches the ledger. It never reaches the user — DraftingService turns
    // every one of these into the same "We couldn't write this one."
    expect(result.refusalCategory).toBe("I can't help with that.");
  });

  it('treats a refusal as a refusal even when text came with it', async () => {
    const mixed = response({
      output_text: 'Happy birthday!',
      output: [
        {
          type: 'message',
          id: 'msg_1',
          status: 'completed',
          role: 'assistant',
          content: [
            { type: 'refusal', refusal: 'declined' },
            { type: 'output_text', text: 'Happy birthday!', annotations: [] },
          ],
        },
      ],
    } as Partial<OpenAIResponse>);

    // Checked BEFORE the text is read, so partial output alongside a decline is not served.
    const result = await provider(clientReturning(mixed)).draft(PROMPT);
    expect(result.outcome).toBe('REFUSED');
    expect(result.text).toBe('');
  });

  it('refuses to hand over a message that was cut off mid-word', async () => {
    // Half a birthday message is worse than the local template: the user is one tap from
    // sending a sentence that stops mid-word to somebody they care about, and nothing on
    // the screen would tell them.
    const cut = response({
      status: 'incomplete',
      incomplete_details: { reason: 'max_output_tokens' },
      output_text: 'Many happy ret',
    } as Partial<OpenAIResponse>);

    const result = await provider(clientReturning(cut)).draft(PROMPT);
    expect(result.outcome).toBe('ERROR');
    expect(result.text).toBe('');
    expect(result.errorMessage).toContain('max_output_tokens');
  });

  it('treats a completed response with no text as an error, not as an empty draft', async () => {
    const result = await provider(clientReturning(response({ output_text: '' }))).draft(PROMPT);
    expect(result.outcome).toBe('ERROR');
  });

  it('still reports the tokens a refused call spent', async () => {
    // A refusal cost money. The ledger has to see it, or the rate limit and the bill
    // disagree about what happened.
    const refused = response({
      output_text: '',
      usage: { ...EMPTY_USAGE, input_tokens: 800, output_tokens: 15 },
      output: [
        {
          type: 'message',
          id: 'msg_1',
          status: 'completed',
          role: 'assistant',
          content: [{ type: 'refusal', refusal: 'declined' }],
        },
      ],
    } as Partial<OpenAIResponse>);

    const result = await provider(clientReturning(refused)).draft(PROMPT);
    expect(result.usage.inputTokens).toBe(800);
    expect(result.usage.outputTokens).toBe(15);
  });
});

describe('draft — failures never throw', () => {
  it('turns a thrown error into an ERROR result', async () => {
    // DraftingService writes the ledger row in a `finally`; a provider that threw past it
    // would be the one path that skipped the row.
    const result = await provider(clientReturning(new Error('socket hang up'))).draft(PROMPT);
    expect(result.outcome).toBe('ERROR');
    expect(result.usage).toEqual({
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
    });
  });

  it('distinguishes a timeout, because it is the one the composer is most likely to meet', async () => {
    const timeout = new Error('Request timed out.');
    const result = await provider(clientReturning(timeout)).draft(PROMPT);
    expect(result.outcome).toBe('TIMEOUT');
  });

  it('never puts the prompt or a stack into the error message', async () => {
    // §11 bans PII in logs, and an error message is a log line that also crosses a layer.
    const err = new Error('connect ECONNREFUSED 127.0.0.1:443 while sending Anika Pillai');
    const result = await provider(clientReturning(err)).draft(PROMPT);
    expect(result.errorMessage).not.toContain('Anika');
    expect(result.errorMessage).not.toContain('ECONNREFUSED');
  });
});

/**
 * Structured outputs, and the one retry (REQUIREMENTS.md §10 rule 3).
 *
 * "Structured outputs only — JSON schema enforced and validated server-side; a malformed
 * response is retried once, then falls back to a template." M5's own plan (§3.3) promised
 * this and the first version of the adapter sent plain text anyway, which is how a promise
 * in a plan becomes a thing nobody did. Found by checking the branch against §10, not by
 * reading the plan.
 */
describe('parseDraft — validated server-side', () => {
  it('takes the message out of a schema-shaped body', () => {
    expect(parseDraft('{"message":"Many happy returns, Anika."}')).toBe(
      'Many happy returns, Anika.',
    );
  });

  it('refuses anything that is not that shape', () => {
    // `strict: true` is the API's promise. This is ours — the half of the rule that is
    // about not trusting the other side.
    for (const body of [
      'Many happy returns.', // plain text: the old behaviour
      '{"message":123}',
      '{"message":""}',
      '{"message":"   "}',
      '{"wish":"wrong key"}',
      '["message"]',
      '{',
      '',
      null,
      undefined,
    ]) {
      expect(parseDraft(body as string | null | undefined)).toBeNull();
    }
  });

  it('trims, because a schema does not stop a model padding a string', () => {
    expect(parseDraft('{"message":"  Happy birthday.  "}')).toBe('Happy birthday.');
  });
});

describe('draft — the one retry', () => {
  /** Returns each response in turn, so a retry gets a different answer from the first call. */
  function clientReturningEach(values: OpenAIResponse[]): { client: OpenAI; calls: () => number } {
    let i = 0;
    return {
      client: {
        responses: {
          create: async () => {
            const value = values[Math.min(i, values.length - 1)];
            i += 1;
            return value;
          },
        },
      } as unknown as OpenAI,
      calls: () => i,
    };
  }

  const good = (text = '{"message":"Many happy returns."}') =>
    response({
      output_text: text,
      usage: { ...EMPTY_USAGE, input_tokens: 100, output_tokens: 20 },
    });

  it('retries ONCE when the body does not match the schema, and succeeds', async () => {
    const { client, calls } = clientReturningEach([good('not json at all'), good()]);
    const result = await provider(client).draft(PROMPT);

    expect(result.outcome).toBe('OK');
    expect(result.text).toBe('Many happy returns.');
    expect(calls()).toBe(2);
  });

  it('bills BOTH attempts, because both were charged', async () => {
    // A ledger that recorded only the successful attempt would quietly under-report exactly
    // the calls that cost the most.
    const { client } = clientReturningEach([good('not json'), good()]);
    const result = await provider(client).draft(PROMPT);

    expect(result.usage.inputTokens).toBe(200);
    expect(result.usage.outputTokens).toBe(40);
  });

  it('gives up after the second malformed body, which the caller turns into a template', async () => {
    const { client, calls } = clientReturningEach([good('not json'), good('still not json')]);
    const result = await provider(client).draft(PROMPT);

    expect(result.outcome).toBe('ERROR');
    expect(result.text).toBe('');
    expect(calls()).toBe(2);
    expect(result.usage.inputTokens).toBe(200);
  });

  it('does NOT retry a refusal', async () => {
    // The model declined. Asking again produces another decline at another cost.
    const refused = response({
      output_text: '',
      output: [
        {
          type: 'message',
          id: 'msg_1',
          status: 'completed',
          role: 'assistant',
          content: [{ type: 'refusal', refusal: 'declined' }],
        },
      ],
    } as Partial<OpenAIResponse>);
    const { client, calls } = clientReturningEach([refused, good()]);

    expect((await provider(client).draft(PROMPT)).outcome).toBe('REFUSED');
    expect(calls()).toBe(1);
  });

  it('does NOT retry a thrown error or a timeout', async () => {
    // Both have their own answers, and a blind second attempt doubles the worst case.
    const result = await provider(clientReturning(new Error('Request timed out.'))).draft(PROMPT);
    expect(result.outcome).toBe('TIMEOUT');
  });

  it('does NOT retry a truncated response', async () => {
    // With a JSON schema a cut-off body is also unparseable — but the cause is
    // max_output_tokens, and the same request produces the same truncation.
    const cut = response({
      status: 'incomplete',
      incomplete_details: { reason: 'max_output_tokens' },
      output_text: '{"message":"Many happy ret',
    } as Partial<OpenAIResponse>);
    const { client, calls } = clientReturningEach([cut, good()]);

    const result = await provider(client).draft(PROMPT);
    expect(result.outcome).toBe('ERROR');
    expect(result.errorMessage).toContain('max_output_tokens');
    expect(calls()).toBe(1);
  });
});

/**
 * What the adapter actually ASKS for.
 *
 * Added after a revert-check went green: deleting `text: { format: DRAFT_SCHEMA }` from the
 * request broke nothing, because every test above drives a fake client that ignores the
 * request and returns a canned response. `parseDraft` tests our half of §10 rule 3; this
 * tests the half that makes the API enforce anything at all.
 */
describe('the request the adapter sends', () => {
  function capturingClient(): { client: OpenAI; body: () => Record<string, unknown> } {
    let seen: Record<string, unknown> = {};
    return {
      client: {
        responses: {
          create: async (body: Record<string, unknown>) => {
            seen = body;
            return response({ output_text: '{"message":"Many happy returns."}' });
          },
        },
      } as unknown as OpenAI,
      body: () => seen,
    };
  }

  it('asks for the JSON schema, strictly (§10 rule 3)', async () => {
    const { client, body } = capturingClient();
    await provider(client).draft(PROMPT);

    const format = (body().text as { format?: Record<string, unknown> })?.format;
    expect(format?.type).toBe('json_schema');
    expect(format?.strict).toBe(true);
    expect((format?.schema as { required?: string[] })?.required).toEqual(['message']);
    // additionalProperties:false is what stops the model adding a "notes" field beside the
    // message and the parse silently ignoring it.
    expect((format?.schema as { additionalProperties?: boolean })?.additionalProperties).toBe(
      false,
    );
  });

  it('sends the system prompt as instructions and the volatile half as input', async () => {
    // The cache layout from prompt-builder.ts expressed in this API's terms: `instructions`
    // is the frozen prefix, of which there are exactly two in the whole app.
    const { client, body } = capturingClient();
    await provider(client).draft({ system: 'SYSTEM HALF', user: 'VOLATILE HALF' });

    expect(body().instructions).toBe('SYSTEM HALF');
    expect(body().input).toBe('VOLATILE HALF');
  });

  it('pins the cache key to the prompt family and carries nothing about anybody', async () => {
    const { client, body } = capturingClient();
    await provider(client).draft(PROMPT);

    // Not per user and not per person: a key that varied per request would give every user
    // their own entry for a prefix identical to everyone's, which is the same miss as
    // having no key at all.
    expect(body().prompt_cache_key).toBe('wishkaro-wish-draft-gpt-6-luna');
    expect(body().reasoning).toEqual({ effort: 'low' });
  });
});
