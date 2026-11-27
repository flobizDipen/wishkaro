import { createHash } from 'node:crypto';
import type { EventType, Sentiment } from '@prisma/client';
import { BANNED_ON_SOLEMN, type PosterTheme } from './theme-library';

/**
 * The two prompts M6 adds, each versioned separately (§10 rule 4, "prompts are versioned
 * files in the repo").
 *
 * M5's `PROMPT_VERSION` covers the wish draft and is not touched. Two more are needed rather
 * than one because the two calls change for different reasons and at different times: the
 * copy prompt changes when the words read wrong, the art prompt changes when TBD-5 lands.
 * Sharing a version would mean a copy edit silently invalidating every hand-review of the
 * artwork, and FR-POS-04's "hand-approved" is only worth something if it can be traced to a
 * revision — which is the whole point of the rule.
 *
 * Bump on any edit to the text below. A ledger row carries the version that produced it, so
 * a bump never re-labels what has already been written.
 */
export const POSTER_COPY_PROMPT_VERSION = 'poster-copy-2026-11-27';
export const POSTER_ART_PROMPT_VERSION = 'poster-art-2026-11-27';

export interface CopyPromptInput {
  displayName: string;
  relationToOwner: string | null;
  eventType: EventType;
  sentiment: Sentiment;
  milestone: string | null;
  language: string;
  /** The ids the model may choose from — already filtered to the occasion's family. */
  themeIds: readonly string[];
  /** FR-PPL-09. A flagged record is not profiled. */
  isMinor: boolean;
}

/**
 * FR-POS-01 step (a): the words and the theme.
 *
 * ## What it is allowed to know
 *
 * The relation, the occasion, the milestone and the language. **Not** hobbies, likes, notes
 * or anything else a wish draft gets. A headline is three or four words; nothing in a
 * person's profile can change them, so sending the profile would be paying input tokens to
 * leak more about somebody than the output could possibly use.
 *
 * FR-PPL-09's minor flag narrows it further, to exactly what `prompt-builder.ts` already
 * narrows a wish draft to: relation, occasion, milestone and age band, nothing else. Here
 * that means the relation goes too.
 *
 * ## The split, and why it is not cosmetic
 *
 * `system` is frozen per (sentiment, language) and `user` carries this person. That is the
 * cache layout `prompt-builder.ts` established: the prefix is what a provider can cache,
 * and a prefix that varied per person would be a prefix that never hits.
 */
/**
 * Every user-typed string the copy prompt carries. Beside the builder, for the reason
 * `prompt-builder.ts` gives: a list kept anywhere else drifts towards checking less.
 *
 * Shorter than the wish draft's, because this prompt is deliberately told less — a
 * headline is three or four words and nothing in a person's profile can change them.
 * `isMinor` suppresses the relation here exactly as it does in the prompt itself.
 */
export function userTextIn(input: CopyPromptInput): string[] {
  return [input.displayName, input.isMinor ? null : input.relationToOwner].filter(
    (v): v is string => typeof v === 'string' && v.trim() !== '',
  );
}

export function buildCopyPrompt(input: CopyPromptInput): { system: string; user: string } {
  const solemn = input.sentiment === 'SOLEMN';

  const system = [
    'You write the few words that go on a greeting poster, and you choose its theme.',
    'The headline is three or four words at most. It is set at 72 pixels over a photograph,',
    'so anything longer does not fit and will be cropped.',
    solemn
      ? 'This is a REMEMBRANCE. The register is quiet, plain and dignified. Never congratulate,' +
        ' never wish anyone a happy anything, never mention balloons, cake, parties or' +
        ' celebration, and never imply the person is alive.'
      : 'This is a celebration. The register is warm and direct.',
    'No emoji. No quotation marks. No trailing full stop. No hashtags.',
    `Write in ${input.language.toLowerCase()}.`,
    'Choose exactly one theme id from the list you are given. Nothing outside that list.',
  ].join(' ');

  const facts: string[] = [
    `Name as it should appear: ${input.displayName}`,
    `Occasion: ${describeOccasion(input.eventType, solemn)}`,
  ];
  // FR-PPL-09: a flagged record is excluded from profiling. The relation is the only
  // profile field this prompt ever carries, so suppressing it is the whole of the rule here.
  if (!input.isMinor && input.relationToOwner) {
    facts.push(`Their relation to the sender: ${input.relationToOwner}`);
  }
  if (input.milestone) {
    facts.push(`Milestone to acknowledge if it fits: ${input.milestone}`);
  }
  facts.push(`Theme ids you may choose from: ${input.themeIds.join(', ')}`);

  return { system, user: facts.join('\n') };
}

function describeOccasion(type: EventType, solemn: boolean): string {
  if (solemn && type !== 'DEATH_ANNIVERSARY') {
    // A remembrance birthday (FR-EVT-05). Saying "birthday" alone here is how a model
    // writes "Happy birthday" for somebody who has died.
    return `a remembrance on what would have been their ${type.toLowerCase().replace(/_/g, ' ')}`;
  }
  switch (type) {
    case 'DEATH_ANNIVERSARY':
      return 'a death anniversary — a remembrance';
    case 'BIRTHDAY':
      return 'a birthday';
    case 'WEDDING_ANNIVERSARY':
      return 'a wedding anniversary';
    case 'ENGAGEMENT_ANNIVERSARY':
      return 'an engagement anniversary';
    case 'CUSTOM':
      return 'an occasion the sender named themselves';
  }
}

export interface ArtPromptInput {
  theme: PosterTheme;
  eventType: EventType;
  sentiment: Sentiment;
  milestone: string | null;
}

/**
 * The art-direction prompt for step (b) — **the background, and nothing else**.
 *
 * FR-POS-01(b): "`gpt-image-1` generates background artwork **only**." FR-POS-02 is why, and
 * it is a likeness and personality-rights rule rather than an aesthetic one: the person's
 * face is never passed through a generative model in the default path. `ImageProvider` has
 * no image input at all, so this prompt is the only thing that reaches the model, and it
 * carries no name, no relation, no trait and no photo.
 *
 * ## What it deliberately does NOT contain
 *
 * **No person.** Not their name, not "a photo of", not an age. §11 bans PII where it does
 * not belong and a prompt is sent to a third party; FR-PPL-09's minor flag would also have
 * to be honoured here, and the cleanest way to honour it is to have nothing to suppress.
 *
 * **No text.** Image models render text badly and the words are composited by `sharp` at a
 * known size in a known font. Asking for a caption would produce a second, misspelled one
 * underneath ours.
 *
 * **No faces, no figures.** A generated face in the background of a poster about a real
 * person is the same personality-rights problem arriving through the back door.
 *
 * The negative list for a solemn occasion is `BANNED_ON_SOLEMN`, the same table the picker
 * filter and the client are held to (`docs/fixtures/poster-themes.json`). Two lists would be
 * two chances to forget "confetti".
 */
export function buildArtPrompt(input: ArtPromptInput): string {
  const { theme } = input;
  const register =
    input.sentiment === 'SOLEMN'
      ? 'Quiet, restrained and dignified. Muted, desaturated colours.'
      : 'Warm, celebratory and bright.';

  const forbidden =
    input.sentiment === 'SOLEMN'
      ? `Absolutely no ${BANNED_ON_SOLEMN.join(', ')}.`
      : 'No text of any kind.';

  return [
    `A background image for a greeting poster. ${register}`,
    `Style: ${theme.displayName.replace(/ — placeholder$/, '')}.`,
    `Palette: ${theme.palette}, from ${theme.background.from} to ${theme.background.to}.`,
    `Motifs: ${theme.motifs.join(', ')}.`,
    'Abstract or decorative only. No people, no faces, no figures, no hands, no animals.',
    'No words, letters, numbers or logos anywhere in the image.',
    'Leave the centre and lower third visually calm: a photograph and text are composited over them.',
    forbidden,
  ].join(' ');
}

/**
 * A stable fingerprint of what was actually asked, for `generation.prompt_hash` (§8).
 *
 * The prompt TEXT is never stored. For the art prompt that would be harmless; for the copy
 * prompt it carries a person's name, their relation and their traits, and §11 says PII does
 * not go where it does not belong. A hash answers the question the column exists for — "was
 * this the same request as that one" — without keeping the sentence.
 *
 * SHA-256 truncated to 16 hex characters. Not a security boundary: it only has to not
 * collide across one product's prompts, and 64 bits is far past that.
 */
export function promptHashOf(prompt: string): string {
  return createHash('sha256').update(prompt, 'utf8').digest('hex').slice(0, 16);
}
