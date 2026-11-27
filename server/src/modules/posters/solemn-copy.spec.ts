import { solemnCopyViolation } from './poster-copy';

/**
 * The gate on a MODEL's words (FR-POS-13, REQUIREMENTS.md §11).
 *
 * `poster-copy.spec.ts` covers the deterministic table, which cannot produce celebratory
 * words for a remembrance because the SOLEMN branch returns before the event type is read.
 * This covers the path that had no such structure: the live copy call, whose headline was
 * taken verbatim with only its `themeId` re-validated.
 */
describe('solemnCopyViolation — the live path held to the table’s rule', () => {
  it('catches the headline that would actually be returned', () => {
    // The concrete failure: a remembrance birthday (FR-EVT-05 keeps type = BIRTHDAY), so
    // a model that reads only the type writes exactly this.
    expect(solemnCopyViolation('SOLEMN', 'Happy Birthday, Asha', null)).toBe('happy');
  });

  it('catches every motif FR-POS-13 names, in either line', () => {
    for (const term of ['balloons', 'confetti', 'cake', 'party', 'fireworks', 'congratulations']) {
      expect(solemnCopyViolation('SOLEMN', `Remembering Asha`, `with ${term}`)).toBe(term);
      expect(solemnCopyViolation('SOLEMN', `A ${term} for Asha`, null)).toBe(term);
    }
  });

  it('is case-insensitive', () => {
    expect(solemnCopyViolation('SOLEMN', 'HAPPY BIRTHDAY', null)).toBe('happy');
  });

  it('allows the template copy it is meant to protect', () => {
    expect(solemnCopyViolation('SOLEMN', 'Remembering Asha Kulkarni', '12 years')).toBeNull();
    expect(solemnCopyViolation('SOLEMN', 'In memory of Asha', null)).toBeNull();
  });

  it('does not fire on a NAME that merely contains a banned word', () => {
    /**
     * Word-boundary matched. A false positive silently swaps the model's good headline for
     * the template — quieter than the bug, still wrong — and these are real Indian names.
     */
    expect(solemnCopyViolation('SOLEMN', 'Remembering Partha Chakrabarti', null)).toBeNull();
    expect(solemnCopyViolation('SOLEMN', 'Remembering Harshad Cakrabarti', null)).toBeNull();
  });

  it('never fires on a celebratory occasion — that is the whole point of the branch', () => {
    expect(solemnCopyViolation('CELEBRATORY', 'Happy Birthday, Asha', 'with cake!')).toBeNull();
  });

  it('handles empty and absent copy', () => {
    expect(solemnCopyViolation('SOLEMN', '', null)).toBeNull();
    expect(solemnCopyViolation('SOLEMN', null, undefined)).toBeNull();
  });
});
