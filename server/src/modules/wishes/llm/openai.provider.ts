import { Logger } from '@nestjs/common';
import OpenAI from 'openai';
import type { Response as OpenAIResponse } from 'openai/resources/responses/responses';
import type { DraftPrompt, DraftResult, LlmProvider, TokenUsage } from './llm-provider';

/**
 * Wish drafting on OpenAI.
 *
 * **D20 in `REQUIREMENTS.md` said "Text AI = Claude".** Dipen changed that on 2026-11-26,
 * after being shown the conflict, and chose to run both providers behind this port rather
 * than replace one with the other — so that the solemn copy TBD-4 has to hand-review can be
 * compared on real output from both instead of settled by picking a vendor first. D20 and
 * §10 carry the amendment, the way M3 amended FR-IMP-04 and M4 amended FR-VIEW-06.
 *
 * ## What is NOT verified
 *
 * There is no OpenAI key on the build machine, so **nothing in this file has made a real
 * request.** The field names come from the installed SDK's own type declarations rather than
 * from memory, and the mapping below is covered by tests built from those types — but a type
 * is a claim about a shape, not a recording of one. Everything here is PARTIAL until a key
 * exists, and `M5_VERIFY.md` says so rather than rounding it up.
 *
 * The one failure that would be silent is usage coming back in fields this code does not
 * read: the ledger would record zeros, every draft would look free, and nothing would break.
 * [readUsage] therefore refuses to be quiet about it — see the warning there.
 *
 * ## Three ways this is not the Anthropic adapter
 *
 * 1. **No server-side fallbacks.** Anthropic's `fallbacks: "default"` re-runs a declined
 *    request on another model inside the same call. OpenAI has no equivalent, so a refusal
 *    here goes straight to the app's local template with no second attempt. That is a real
 *    reduction in what §5.3 of the plan promised, and it is written down rather than papered
 *    over. A client-side retry on a second model is possible and is deliberately not built:
 *    it doubles the cost of the worst case and it is Dipen's call, not a session's.
 * 2. **Caching is automatic.** There is no `cache_control` breakpoint to place. The prefix
 *    still has to be stable for it to work at all, so the two-system-prompt layout in
 *    `prompt-builder.ts` earns its keep either way, and `prompt_cache_key` pins the routing.
 * 3. **`input_tokens` is a TOTAL, not a remainder.** See [readUsage]. This is the one that
 *    would quietly corrupt the ledger.
 */
export class OpenAiProvider implements LlmProvider {
  readonly providerName = 'openai';
  readonly isLive = true;

  private readonly logger = new Logger(OpenAiProvider.name);
  private readonly client: OpenAI;

  constructor(
    apiKey: string,
    readonly modelName: string,
    private readonly effort: 'low' | 'medium' | 'high',
    /** Injectable so a test can drive the mapping without a network. */
    client?: OpenAI,
  ) {
    this.client =
      client ??
      new OpenAI({
        apiKey,
        // A draft the user is waiting on behind a spinner. Longer than this and the composer
        // should have fallen back to its local template already — the user tapped a reminder
        // because they are about to walk into a room.
        timeout: 20_000,
        // The SDK retries 429s and 5xx by default. One is right: a second retry turns a
        // 20-second timeout into a minute of somebody staring at a spinner.
        maxRetries: 1,
      });
  }

  async draft(prompt: DraftPrompt): Promise<DraftResult> {
    try {
      const response = await this.client.responses.create({
        model: this.modelName,
        /**
         * The system prompt as `instructions`, the volatile half as `input`.
         *
         * That split is the cache layout from `prompt-builder.ts` expressed in this API's
         * terms: `instructions` is the frozen prefix and there are exactly two of them in
         * the whole app, so it is the part worth caching.
         */
        instructions: prompt.system,
        input: prompt.user,
        /**
         * Pins cache routing to the prompt family rather than letting it be inferred.
         *
         * Deliberately NOT per user or per person: a key that varied per request would give
         * every user their own cache entry for a prefix that is identical for all of them,
         * which is the same miss as having no key at all. It carries nothing about anybody —
         * §11 bans PII in anything that leaves this server beyond the prompt itself.
         */
        prompt_cache_key: `wishkaro-wish-draft-${this.modelName}`,
        reasoning: { effort: this.effort },
        // A greeting. Generous enough for a Devanagari draft, which runs to far more tokens
        // than the same message in English, and far short of anything that could run away.
        max_output_tokens: 1200,
      });

      return this.interpret(response);
    } catch (cause) {
      /**
       * Never throws — every outcome is a value.
       *
       * `DraftingService` writes the ledger row in a `finally` and a provider that threw
       * past it would be the one path that skipped the row. The service catches as well,
       * because "contracted not to throw" is not "cannot".
       */
      // Both spellings. The SDK's own message is "Request timed out.", so a regex looking
      // only for "timeout" matched nothing and every timeout was recorded as a plain ERROR —
      // found by the test below, not by reading. The ledger would have been merely imprecise;
      // the composer's retry advice is what actually depends on telling these apart.
      const timedOut =
        cause instanceof OpenAI.APIConnectionTimeoutError ||
        (cause instanceof Error && /timed?\s*out/i.test(cause.message));

      this.logger.warn(`OpenAI draft failed: ${describe(cause)}`);
      return {
        outcome: timedOut ? 'TIMEOUT' : 'ERROR',
        text: '',
        model: this.modelName,
        // Nothing came back, so nothing is known about what it cost. Zeros that are visibly
        // a gap, not a guess.
        usage: { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
        errorMessage: describe(cause),
      };
    }
  }

  /**
   * Read the response, checking for a refusal BEFORE reading any text.
   *
   * The Anthropic equivalent of this trap is `stop_reason: "refusal"` arriving as an HTTP
   * 200. Here it is a content part of `type: 'refusal'` sitting where a text part would be,
   * on a response whose status is otherwise unremarkable. Code that collects text parts and
   * ignores the rest shows the user an empty composer and no error at all — and a
   * remembrance prompt about a dead relative is exactly the shape that can trip a
   * classifier, so this is the path the solemn composer is most likely to meet.
   */
  private interpret(response: OpenAIResponse): DraftResult {
    const usage = readUsage(response, this.logger, this.modelName);
    const base = { model: response.model ?? this.modelName, usage };

    const refusal = firstRefusal(response);
    if (refusal !== null) {
      return { ...base, outcome: 'REFUSED', text: '', refusalCategory: refusal || null };
    }

    /**
     * `incomplete` means the model was cut off — almost always by `max_output_tokens`.
     *
     * Treated as an ERROR rather than handed over as a partial. Half a birthday message is
     * worse than the local template: the user is one tap from sending a sentence that stops
     * mid-word to somebody they care about, and nothing on the screen would tell them.
     */
    if (response.status === 'incomplete') {
      return {
        ...base,
        outcome: 'ERROR',
        text: '',
        errorMessage: `incomplete: ${response.incomplete_details?.reason ?? 'unknown'}`,
      };
    }

    if (response.status === 'failed') {
      return {
        ...base,
        outcome: 'ERROR',
        text: '',
        errorMessage: response.error?.message ?? 'failed',
      };
    }

    const text = (response.output_text ?? '').trim();
    if (text === '') {
      // Belt and braces. A completed response with no text is not something this code
      // understands, and serving an empty composer is the one outcome worth never shipping.
      return { ...base, outcome: 'ERROR', text: '', errorMessage: 'no text in response' };
    }

    return { ...base, outcome: 'OK', text };
  }
}

/**
 * The refusal string, or null when there was not one.
 *
 * Returns the string rather than a boolean so the category reaches the ledger. It never
 * reaches the user: the screen where somebody is writing about a death is the worst place in
 * this app to surface a classifier's opinion of what they asked for.
 */
function firstRefusal(response: OpenAIResponse): string | null {
  for (const item of response.output ?? []) {
    if (item.type !== 'message') continue;
    for (const part of item.content ?? []) {
      if (part.type === 'refusal') return part.refusal ?? '';
    }
  }
  return null;
}

/**
 * OpenAI's usage, normalised to the DISJOINT contract in `llm-provider.ts`.
 *
 * **`input_tokens` is the TOTAL here, with `cached_tokens` and `cache_write_tokens` as
 * subsets of it.** Anthropic's `input_tokens` is the remainder after those. Passing OpenAI's
 * numbers straight through would charge the cached tokens twice — once at the full input
 * rate inside the total and again at the cache rate — so the ledger would read high by
 * exactly the amount caching was supposed to save, and the conclusion a reader would draw
 * from it is "caching is not worth having". That is worse than no ledger.
 *
 * Clamped at zero: if the subsets ever exceed the total, the arithmetic is not something
 * this code understands and a negative token count would silently become a negative cost.
 */
export function readUsage(
  response: Pick<OpenAIResponse, 'usage'>,
  logger?: Logger,
  model?: string,
): TokenUsage {
  const usage = response.usage;

  if (!usage) {
    // The silent failure this whole function exists to prevent: no usage means a ledger row
    // of zeros, which reads as a free draft. Say so.
    logger?.warn(
      `OpenAI returned no usage for ${model ?? 'a draft'} — its ledger row will read zero. ` +
        'The response shape has changed; check readUsage against the SDK types.',
    );
    return { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 };
  }

  const cacheReadTokens = usage.input_tokens_details?.cached_tokens ?? 0;
  const cacheWriteTokens = usage.input_tokens_details?.cache_write_tokens ?? 0;
  const total = usage.input_tokens ?? 0;

  return {
    inputTokens: Math.max(0, total - cacheReadTokens - cacheWriteTokens),
    // Reasoning tokens are inside `output_tokens` and are billed at the output rate, so
    // there is nothing to subtract here — and nothing to add either. The breakdown is in
    // `output_tokens_details.reasoning_tokens` if anybody ever needs to see how much of a
    // greeting's cost was the model thinking about it.
    outputTokens: usage.output_tokens ?? 0,
    cacheReadTokens,
    cacheWriteTokens,
  };
}

/** Something a developer can act on. Never shown to a user, and never carrying the prompt. */
function describe(cause: unknown): string {
  if (cause instanceof OpenAI.APIError) return `${cause.status ?? '?'} ${cause.name}`;
  if (cause instanceof Error) return cause.name;
  return 'unknown error';
}
