import type { EventType, Sentiment, WishLanguage, WishTone } from '@prisma/client';
import type { DraftPrompt } from './llm/llm-provider';
import type { PreviousWish } from './wish-history.service';

/**
 * Turns what we know about a person into what we ask a model for.
 *
 * Pure — no Prisma, no NestJS, no I/O — which is the same arrangement `events/derived.ts`
 * has and for the same reason: the rules carrying the most product risk should be testable
 * without a database. The tests that assert on the PROMPT are here; the tests that assert on
 * the OUTPUT are in `live/`, against a real model, because a test that asserts on a fake
 * model's output tests the fake.
 *
 * ## The cache layout, which is the whole reason this file is shaped as it is
 *
 * Prompt caching is a PREFIX match: any byte change anywhere in the prefix invalidates
 * everything after it. So there are exactly TWO system prompts in this app — one celebratory,
 * one solemn — and each carries EVERY tone and language instruction rather than only the
 * selected one. Putting the chosen tone in the system prompt would give eighteen distinct
 * prefixes per family and make most calls a cache miss; stating the choice in the user
 * message instead keeps the prefix frozen across every request of that family.
 *
 * Nothing volatile may cross into [DraftPrompt.system]: no timestamp, no request id, no name.
 * docs/M5_PLAN.md §3.3, and the measurement that proves it is reading
 * `usage.cache_read_input_tokens` rather than assuming.
 */

/**
 * What the server knows, assembled from its own tables.
 *
 * Note what is NOT here: nothing the client said about the person. The client sends ids and
 * choices; meanings are looked up. `sentiment` in particular is derived server-side, so the
 * tone restriction holds even against a client that has been tampered with.
 */
export interface DraftContext {
  displayName: string;
  relationToOwner: string | null;
  eventType: EventType;
  sentiment: Sentiment;
  occurrenceYear: number;
  /** Null when the year of birth is unknown — a first-class case (FR-EVT-02). */
  age: number | null;
  /** "25 years", "Silver" — never a celebratory milestone name on a solemn occasion. */
  milestoneLabel: string | null;
  hobbies: string[];
  likes: string[];
  notes: string | null;
  /**
   * FR-PPL-09's derived flag. When true the prompt is NARROWED — see [personBlock].
   * REQUIREMENTS.md §12: "Minor flag suppresses profiling, personalisation and affiliate
   * tracking." Raised as a collision with FR-WSH-02 in docs/M5_PLAN.md §6.2.
   */
  isMinor: boolean;
  tone: WishTone;
  language: WishLanguage;
  /** Previous years, newest first. FR-WSH-02's "must not repeat". */
  previous: PreviousWish[];
  /** Drafts produced THIS session that the user rejected with Regenerate (FR-WSH-04). */
  avoid: string[];
}

// ---------------------------------------------------------------------------------------
// The two system prompts. Chosen before anything is built, never merged with a conditional.
// ---------------------------------------------------------------------------------------

const SHARED_RULES = [
  'Output only the message itself. No preamble, no sign-off from you, no explanation,',
  'no quotation marks around it, and no alternatives to choose from.',
  'Write as the sender, in first person, to the recipient.',
  'Never invent a fact about the recipient that you were not given — no imagined memories,',
  'no imagined plans, no imagined gifts, and nothing about their appearance or their health.',
  'Do not use the recipient’s name more than once.',
  'Do not mention that this message was drafted, assisted or generated.',
].join(' ');

const TONE_RULES = [
  'The TONE you are asked for means:',
  '- Warm: affectionate and direct. Two or three sentences.',
  '- Funny: light and teasing, never mocking, never about age, weight, money or marriage.',
  '- Respectful: formal and unhurried. Suitable for an elder, a colleague or a boss.',
  '- Short: one sentence. Under twenty words. No preamble at all.',
  '- Poetic: two to four lines with a clear rhythm. Rhyme only if it is not forced.',
  '- Heartfelt: plain, unhurried and sincere. No ornament and no performance of grief.',
].join('\n');

const LANGUAGE_RULES = [
  'The LANGUAGE you are asked for means:',
  '- English: Indian English. Natural, not American.',
  '- Hindi: Hindi in Devanagari script. Not transliterated.',
  '- Hinglish: Hindi and English mixed as urban Indians actually write it, in Latin script.',
  '  Not Hindi with English words dropped in, and not English with "ji" at the end.',
].join('\n');

/**
 * The celebratory family. Birthdays, weddings, engagements, anything the user named — for
 * somebody who is alive.
 */
const CELEBRATORY_SYSTEM = [
  'You write short personal greetings that an Indian user will send to someone they know,',
  'over WhatsApp, for a birthday or an anniversary.',
  '',
  'The user will edit what you write before sending it, so write something worth editing',
  'rather than something safe. A message that could have been sent to anybody has failed.',
  '',
  SHARED_RULES,
  '',
  TONE_RULES,
  '',
  LANGUAGE_RULES,
].join('\n');

/**
 * The solemn family (FR-WSH-09). A separate template, not a flag on the one above.
 *
 * `domain/Tone.kt` makes this argument for the app's static copy and it holds here: a
 * conditional is a thing someone can forget; a set you have to pick is a thing the compiler
 * makes you pick.
 *
 * One difference from `SolemnStrings`, and it is worth stating because the first draft of
 * this file got it wrong. `SolemnStrings` contains none of the forbidden words, so
 * `ToneTest` can assert a banned lexicon against it directly. A PROMPT cannot work that way:
 * it has to name what it forbids, so "never congratulate" is written here, in the file whose
 * job is to prevent congratulation. The banned-lexicon assertion therefore belongs on the
 * OUTPUT, where it is a statement about what the model produced, and that test is in `live/`
 * against a real model. Asserting it against this string would be asserting that the rule is
 * absent rather than that it is obeyed.
 *
 * TBD-4 is open — the art direction for this copy is reviewed by hand, not shipped on a
 * session's say-so. docs/M5_PLAN.md §5.4: the mechanism ships, FR-WSH-09 waits on Dipen
 * reading real output.
 */
const SOLEMN_SYSTEM = [
  'You write short remembrance messages that an Indian user will send to someone who is',
  'grieving, or will post in memory of someone who has died.',
  '',
  'This is not a greeting and it is not a card. It is one person acknowledging another',
  'person’s absence, on the day that absence is loudest.',
  '',
  'Absolute rules, and breaking any of them makes the message unusable:',
  '- Never congratulate, never celebrate, never wish anyone a happy anything.',
  '- No emoji, no exclamation marks, and no decorative punctuation.',
  '- Do not say the death was for the best, part of a plan, or a blessing.',
  '- Do not tell the reader how to feel, how long to grieve, or that time heals.',
  '- Do not describe how the person died, and do not speculate about an afterlife',
  '  unless the user’s own note already does.',
  '- Do not promise anything on the sender’s behalf beyond being present.',
  '',
  SHARED_RULES,
  '',
  TONE_RULES,
  '',
  LANGUAGE_RULES,
].join('\n');

/** The one place a system prompt is chosen. Two families; no third. */
export function systemPromptFor(sentiment: Sentiment): string {
  return sentiment === 'SOLEMN' ? SOLEMN_SYSTEM : CELEBRATORY_SYSTEM;
}

// ---------------------------------------------------------------------------------------
// The volatile half
// ---------------------------------------------------------------------------------------

const OCCASION: Record<EventType, string> = {
  BIRTHDAY: 'their birthday',
  WEDDING_ANNIVERSARY: 'their wedding anniversary',
  ENGAGEMENT_ANNIVERSARY: 'their engagement anniversary',
  DEATH_ANNIVERSARY: 'the anniversary of their death',
  CUSTOM: 'an occasion the sender marks for them',
};

export function buildPrompt(context: DraftContext): DraftPrompt {
  return {
    system: systemPromptFor(context.sentiment),
    user: [
      ...personBlock(context),
      '',
      ...historyBlock(context),
      ...avoidBlock(context),
      `Tone: ${context.tone}`,
      `Language: ${context.language}`,
      '',
      context.sentiment === 'SOLEMN'
        ? 'Write the remembrance message now.'
        : 'Write the message now.',
    ]
      .filter((line) => line !== null)
      .join('\n'),
  };
}

function personBlock(c: DraftContext): string[] {
  const lines = [
    'About the recipient:',
    `- Name: ${c.displayName}`,
    `- Relationship to the sender: ${c.relationToOwner ?? 'not stated'}`,
    `- Occasion: ${OCCASION[c.eventType]}`,
  ];

  if (c.milestoneLabel) lines.push(`- Milestone: ${c.milestoneLabel}`);
  if (c.age !== null) {
    // Only ever stated on a celebratory occasion. A remembrance message does not say "they
    // would have been forty" — a sentence nobody asked this app to produce, and the exact
    // thing SolemnStrings.years() already refuses to render on the person screen.
    if (c.sentiment !== 'SOLEMN') lines.push(`- Age they are turning: ${c.age}`);
  }

  /**
   * THE MINOR NARROWING (docs/M5_PLAN.md §6.2).
   *
   * REQUIREMENTS.md §12 says the minor flag suppresses profiling and personalisation;
   * FR-WSH-02 says condition the draft on hobbies. For a person flagged as a minor those
   * point in opposite directions, and this is the narrowing: relation, occasion and age band
   * only. The draft still knows it is your ten-year-old niece's birthday. It is not profiled.
   *
   * Raised rather than silently chosen, because a narrowing of a requirement is the kind of
   * thing that gets quietly lost.
   */
  if (!c.isMinor) {
    if (c.hobbies.length > 0) lines.push(`- Hobbies: ${c.hobbies.join(', ')}`);
    if (c.likes.length > 0) lines.push(`- Things they like: ${c.likes.join(', ')}`);
    if (c.notes?.trim()) lines.push(`- The sender's own note about them: ${c.notes.trim()}`);
  }

  return lines;
}

/**
 * FR-WSH-02's "must not repeat", and FR-HIS-03's "fed back into every AI prompt so the app
 * visibly improves each year".
 *
 * The previous messages are quoted in full rather than summarised. "Do not repeat last
 * year's message" is unenforceable without the message; a summary of it is exactly the kind
 * of lossy instruction a model satisfies by writing the same thing in different words, which
 * is the failure this requirement is about.
 */
function historyBlock(c: DraftContext): string[] {
  if (c.previous.length === 0) return [];
  return [
    'What the sender has sent this person before. Do not repeat any of these, and do not',
    'rewrite one of them in different words — write something that is not one of these:',
    ...c.previous.map((p) => `- ${p.year}: ${p.text}`),
    '',
  ];
}

/** Regenerate (FR-WSH-04): the drafts the user has already turned down in this sitting. */
function avoidBlock(c: DraftContext): string[] {
  if (c.avoid.length === 0) return [];
  return [
    'The sender has already turned these down just now. Write something genuinely different',
    'in substance, not a reworded version:',
    ...c.avoid.map((text) => `- ${text}`),
    '',
  ];
}
