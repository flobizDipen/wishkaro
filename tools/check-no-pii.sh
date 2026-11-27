#!/usr/bin/env bash
#
# No real person's email address is committed to this public repository.
#
# ## Why this is a script and not only a jest spec
#
# It was only a jest spec for about an hour, and a peer session found the hole: the spec lives
# in the server suite, `.github/workflows/server.yml` is path-filtered to `server/**`,
# `.github/workflows/server.yml` and `docs/fixtures/**` — and **the leak it was written for was
# in `docs/M0_PREREQUISITES.md`**. A pull request that adds an address to `docs/` and touches
# nothing under `server/` never runs the server suite, so the guard would have stayed silent on
# precisely the case that motivated it.
#
# The obvious fix — widening the server workflow to `docs/**` — makes every docs-only pull
# request run the whole server suite, and those filters exist because that was unwanted. So the
# check moved here: no dependencies, no `npm ci`, nothing to install. `.github/workflows/privacy.yml`
# runs it with **no path filter at all**, because a guard that only runs for some paths is not a
# guard, and it costs a checkout and under a second.
#
# `no-pii-in-repo.spec.ts` calls this same script, so a developer running the server suite still
# gets the check and there is exactly ONE definition of the pattern. `MILESTONE_CHECKLIST.md`
# (bought by M3) says a rule implemented twice is held to one shared table of cases; the cheaper
# answer, where it is available, is not to implement it twice.
#
# ## What it looks for, and what it deliberately does not
#
# Addresses at the consumer mail hosts a real person's account lives on, plus this project
# owner's work domain. **Not** a general email regex: `.env.example`, the fixtures and the specs
# are full of deliberately fake addresses, and a check that flagged those would be switched off
# within a week — which is the real failure mode for a check like this one.
#
# `example.invalid`, `example.test`, `example.com` and `wishkaro.local` are reserved by RFC 2606
# and RFC 6761 precisely so they can be used this way. They are what a fixture should use and
# they are not matched.
#
# ## Scope, stated honestly
#
# This checks the WORKING TREE, not history. Removing an address does not unpublish it — git
# history is permanent and public repositories are scraped, exactly as with a leaked secret, and
# unlike a secret an address cannot be rotated. This stops the next one being added; it cannot
# undo one already pushed.
#
# Usage:
#   tools/check-no-pii.sh              # scan; exit 1 and print file:line on a hit
#   tools/check-no-pii.sh --pattern    # print the regex and exit, so one definition serves all

set -euo pipefail

# Hosts a real person's personal account lives on, and this project owner's work domain.
# Narrow on purpose — see above.
PATTERN='[A-Za-z0-9._%+-]+@(gmail|yahoo|outlook|hotmail|icloud|proton|protonmail)\.(com|me)|[A-Za-z0-9._%+-]+@flobiz\.in'

if [[ "${1:-}" == "--pattern" ]]; then
  printf '%s\n' "$PATTERN"
  exit 0
fi

cd "$(git rev-parse --show-toplevel)"

#
# `--untracked` matters more than it looks: plain `git grep` searches TRACKED files only, so a
# brand-new document carrying an address — which is exactly how M9 nearly did it — would not be
# searched at all until after it was committed, which is one push too late.
#
# `-I` skips binary files. The pathspec exclusions are the two files that are ABOUT the pattern
# and therefore legitimately contain it.
#
# `|| true` because `git grep` exits 1 when it finds nothing, which is the passing case, and
# `set -e` would otherwise treat success as failure.
#
hits="$(git grep -nIE --untracked "$PATTERN" -- \
  . \
  ':(exclude)tools/check-no-pii.sh' \
  ':(exclude)server/src/common/no-pii-in-repo.spec.ts' \
  || true)"

if [[ -z "$hits" ]]; then
  echo "no-pii: clean"
  exit 0
fi

#
# File and line only. **Never the match.**
#
# A check that printed what it found would copy the address into CI logs, into the terminal of
# everybody who ran it, and into whatever pasted the output into an issue — which is the thing
# this exists to prevent, performed by the thing meant to prevent it.
#
echo "no-pii: a real email address is in the working tree." >&2
echo "" >&2
while IFS= read -r line; do
  echo "  ${line%%:*}:$(echo "$line" | cut -d: -f2)" >&2
done <<< "$hits"
echo "" >&2
echo "The address itself is deliberately not printed. Open the file and look." >&2
echo "This repository is PUBLIC: see docs/M9_VERIFY.md §14." >&2
exit 1
