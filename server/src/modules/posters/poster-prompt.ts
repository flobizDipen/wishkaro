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
