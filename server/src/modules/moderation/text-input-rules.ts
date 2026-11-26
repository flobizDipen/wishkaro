import type { ModerationCheck } from './moderation-provider';
import { ALLOWED, blocked } from './moderation-provider';

/**
 * The half of input moderation that works with **no key at all**.
 *
 * `docs/M6_PLAN.md` §5 promised this explicitly, and it is worth being precise about what it
 * is and is not. **It is not a classifier and must not be described as one.** It does not
 * know whether text is abusive, hateful or sexual; that is what `MODERATION_PROVIDER=openai`
 * is for, and it needs a key this machine does not have.
 *
 * What it is: three deterministic rules about text on its way **into a prompt**. Each one
 * has a concrete failure it prevents, each is checkable without a model, and none of them is
 * a judgement about what somebody said.
 *
 *  1. **Length.** A free-text field arriving at 50 kB is not a user avoiding a topic, it is
 *     somebody using our prompt as a transport, and it is input tokens on a call we pay for.
 *  2. **Control and zero-width characters.** Invisible characters inside a prompt are how
 *     instructions get smuggled past a human reading the same string. They have no
 *     legitimate use in "don't mention the divorce".
 *  3. **Prompt-injection markers.** FR-WSH-03's `avoid` list goes verbatim into a prompt,
 *     which makes it the one user-controlled string in this product with a direct line to a
 *     model. Nobody types "ignore previous instructions" into it by accident.
 *
 * ## A fourth rule was written and then deleted, which is worth recording
 *
 * The first version of this file also refused text containing a phone number, an email
 * address or a URL, on the grounds that §11 bans PII where it does not belong. **That was
 * wrong twice over.**
 *
 * §11's rule is about *logs*. Sending a person's details to a model is not a leak, it is the
 * feature — FR-WSH-02 puts their relation, hobbies and notes in the prompt on purpose, and
 * FR-PPL-09 narrows that for minors specifically because the general case is intended. So
 * the rule had no requirement behind it.
 *
 * And it would have broken real users. `person.notes` reaches the prompt, so "ring the
 * landline, she never checks WhatsApp" with a number after it would have made that person
 * undraftable for ever, with the composer showing "We couldn't write this one" every time.
 * Over-blocking a legitimate note is a worse outcome than the thing it was guarding against,
 * which was not a problem to begin with.
 *
 * It is written down rather than silently dropped because the next person to look at this
 * file will think of it too.
 */

/**
 * Matched to `WishUpsertSchema.messageText` and `DraftWishSchema.avoid`, both 4000.
 *
 * Deliberately NOT lower. An `avoid` entry is literally the previous draft, so a cap below
 * the draft's own ceiling would refuse the user's own words — the exact failure
 * `wishes.schemas.ts` records having already made once, when `avoid` was capped at 2000 and
 * Regenerate broke on precisely the long message somebody cared most about.
 *
 * This is a transport guard, not an editorial one. It fires at an order of magnitude above
 * anything a person types.
 */
const MAX_FREE_TEXT = 4000;

/** C0 and C1 controls except tab/newline, plus the zero-width and bidi-override block. */
const INVISIBLE = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F​-‏‪-‮⁠-⁤﻿]/;

/**
 * Lower-cased substrings. Deliberately short and boring — a tripwire on the obvious
 * phrasings, not a defence against a determined attacker. Claiming otherwise would be the
 * kind of unmeasured performance claim `MILESTONE_CHECKLIST.md` bans, one domain along.
 */
const INJECTION_MARKERS: readonly string[] = Object.freeze([
  'ignore previous',
  'ignore all previous',
  'ignore the above',
  'disregard previous',
  'disregard the above',
  'system prompt',
  'forget your instructions',
  '</system>',
  '<|im_start|>',
  '<|im_end|>',
]);

/**
 * Check one piece of free text bound for a prompt.
 *
 * Returns ALLOWED rather than UNCHECKED when nothing fires, and the distinction matters:
 * these rules genuinely ran and genuinely passed. UNCHECKED is reserved for the classifier
 * that was never called.
 */
export function checkFreeText(text: string | null | undefined): ModerationCheck {
  if (text == null || text.trim() === '') return ALLOWED;

  if (text.length > MAX_FREE_TEXT) {
    return blocked(['length'], 'INPUT_TOO_LONG');
  }
  if (INVISIBLE.test(text)) {
    return blocked(['invisible_characters'], 'INPUT_INVISIBLE_CHARACTERS');
  }

  const lowered = text.toLowerCase();
  if (INJECTION_MARKERS.some((m) => lowered.includes(m))) {
    return blocked(['prompt_injection'], 'INPUT_PROMPT_INJECTION');
  }

  return ALLOWED;
}

/** Several fields at once; the first block wins, so the caller gets one reason. */
export function checkFreeTexts(texts: readonly (string | null | undefined)[]): ModerationCheck {
  for (const text of texts) {
    const check = checkFreeText(text);
    if (check.verdict === 'BLOCKED') return check;
  }
  return ALLOWED;
}
