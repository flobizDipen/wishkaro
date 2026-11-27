import { Injectable } from '@nestjs/common';
import type {
  DraftPrompt,
  DraftResult,
  LlmProvider,
  DraftOutcome,
  PosterCopyPrompt,
  PosterCopyResult,
} from './llm-provider';

/**
 * A provider that never calls anything.
 *
 * Two jobs, and it is important they are both small ones:
 *
 * 1. **CI and local development with no key.** Every mechanism around the call — the ledger
 *    row, the rate limit, the tone restriction, the error shapes — is exercised against this.
 * 2. **Forcing an outcome on demand**, so that a refusal and a provider error can be
 *    *demonstrated* rather than argued (docs/BUILD_PROMPT_M5.md, definition of done item 5).
 *
 * What it is NOT for: anything about words. `isLive` is false, so a draft it produces is
 * recorded as `TEMPLATE` rather than `AI` and the history does not claim a model wrote it.
 * No test in this project asserts on the text below — `MILESTONE_CHECKLIST.md` §D, and the
 * build prompt's "a test that asserts on a fake model's output tests the fake".
 */
@Injectable()
export class FakeLlmProvider implements LlmProvider {
  readonly providerName = 'fake';
  readonly modelName = 'fake-model';
  readonly isLive = false;

  /**
   * Forces the next call's outcome. Set by a test, or by `WISH_DRAFT_FORCE` in a local
   * environment so that item 5 can be shown on a device without waiting for a classifier to
   * decline something.
   */
  nextOutcome: DraftOutcome = 'OK';

  async draft(prompt: DraftPrompt): Promise<DraftResult> {
    const usage = {
      // Deliberately derived from the prompt's real length rather than a constant, so that a
      // ledger assertion is reading something that moves when the prompt does.
      inputTokens: Math.ceil((prompt.system.length + prompt.user.length) / 4),
      outputTokens: 24,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
    };

    switch (this.nextOutcome) {
      case 'REFUSED':
        return {
          outcome: 'REFUSED',
          text: '',
          model: this.modelName,
          usage,
          refusalCategory: 'simulated',
        };
      case 'ERROR':
        return {
          outcome: 'ERROR',
          text: '',
          model: this.modelName,
          // A provider that failed before producing anything bills nothing, and the row
          // still gets written — with zeros. A gap in the ledger is worse than a zero.
          usage: { ...usage, inputTokens: 0, outputTokens: 0 },
          errorMessage: 'simulated provider error',
        };
      case 'TIMEOUT':
        return {
          outcome: 'TIMEOUT',
          text: '',
          model: this.modelName,
          usage: { ...usage, outputTokens: 0 },
          errorMessage: 'simulated timeout',
        };
      case 'OK':
        return {
          outcome: 'OK',
          text: 'This draft came from the fake provider. No model was called.',
          model: this.modelName,
          usage,
        };
    }
  }

  /**
   * FR-POS-01 step (a), faked.
   *
   * The headline is deliberately flat and the theme is deliberately the FIRST of the
   * allowed set rather than a random one. Both are the same decision: nothing in this
   * project asserts on a fake model's words (`MILESTONE_CHECKLIST.md` §D), so the only
   * useful property here is determinism — a pipeline test that runs twice must get the same
   * cache key, or the cache-hit measurement would be measuring the fake's dice.
   *
   * It never picks a theme outside `themeIds`, because the one thing worth simulating about
   * this call is that the allowed set is respected. The pipeline re-checks regardless.
   */
  async draftPosterCopy(prompt: PosterCopyPrompt): Promise<PosterCopyResult> {
    const usage = {
      inputTokens: Math.ceil((prompt.system.length + prompt.user.length) / 4),
      outputTokens: 12,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
    };

    switch (this.nextOutcome) {
      case 'REFUSED':
        return {
          outcome: 'REFUSED',
          copy: null,
          model: this.modelName,
          usage,
          refusalCategory: 'simulated',
        };
      case 'ERROR':
        return {
          outcome: 'ERROR',
          copy: null,
          model: this.modelName,
          usage: { ...usage, inputTokens: 0, outputTokens: 0 },
          errorMessage: 'simulated provider error',
        };
      case 'TIMEOUT':
        return {
          outcome: 'TIMEOUT',
          copy: null,
          model: this.modelName,
          usage: { ...usage, inputTokens: 0, outputTokens: 0 },
          errorMessage: 'simulated timeout',
        };
      case 'OK':
      default:
        return {
          outcome: 'OK',
          copy: {
            headline: 'A fake headline',
            subline: null,
            // Falls back to '' rather than throwing on an empty allowed set: a provider is
            // contracted never to throw, and the pipeline's `assertThemeAllowed` is what
            // turns a bad id into a refusal.
            themeId: prompt.themeIds[0] ?? '',
          },
          model: this.modelName,
          usage,
        };
    }
  }
}
