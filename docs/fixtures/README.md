# Test fixtures

Every person in every file here is **invented**. The repository is public, and
`MILESTONE_CHECKLIST.md` §F makes that a rule rather than a preference: a real contacts list
or a real family spreadsheet must never be committed, in whole or in part.

| File | What it is for |
|---|---|
| `name-tokens.json` | The ONE table of cases for the name-token rule, read by `NameTokensTest.kt` and `name-tokens.spec.ts`. If the two implementations drift, one of those suites goes red. See `docs/M3_PLAN.md` §3 |
| `messy-birthdays.csv` | Definition-of-done items 3, 6 and 7. Every date format in FR-IMP-06 in one file, an ambiguous row for the dd/MM toggle, a within-file duplicate, and five rows that are deliberately broken in five different ways |
| `family-birthdays.xlsx` | The `.xlsx` path end to end. Written by a real spreadsheet library, so it carries a genuine date cell — a serial number whose only clue is a custom format two indirections away — plus blank cells that must not shift their rows |
| `six-hundred-people.csv` | Definition-of-done item 8: an import larger than the server's 500-row sync cap, so the chunked push is exercised against a real server |

## The broken rows in `messy-birthdays.csv`, and why each one is there

| Row | Breakage | What it proves |
|---|---|---|
| Diya Menon | `31/04/1990` | A date that does not exist is a *failed row with a reason*, not a silent skip and not a crash |
| Vihaan Iyer | `29/02/2023` | 2023 was not a leap year. 29 February with **no** year is a real birthday and must still import — Saanvi Joshi is the other half of that pair |
| Myra Bhatt | `not a date` | Unreadable text, as opposed to a date in a shape we do not know |
| (no name) | empty name | The failure report has to name a row that has no name to call it by |
| Arjun Pillai | empty date | A person with no date, which FR-PPL-01 does not allow |
| Aarav Shah (twice) | duplicate | Definition-of-done item 6: the same person twice in one file is one person, not two |

`Ishaan Patel` is `05/06/1992` — the only genuinely ambiguous row, and the one that changes
when the dd/MM toggle is flipped (5 June under dd/MM, 6 May under MM/dd).

The two spellings of `जयदत्त जांसरी` / `જયદત્ત જાંસરી` are **not** duplicates of each other —
different scripts share no tokens — and that is recorded here so it is understood as a limit
rather than discovered as a bug.
