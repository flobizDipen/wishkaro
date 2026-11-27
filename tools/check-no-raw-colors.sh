#!/usr/bin/env bash
#
# No colour is named outside `ui/theme/`.
#
# ## Why this exists
#
# M7's definition-of-done item 7: *"Zero hardcoded colours outside `ui/theme/`, enforced by
# `tools/check-no-raw-colors.sh` behind an unfiltered workflow."*
#
# The reason it is worth enforcing rather than tidying once: this app's identity propagates
# because **210** of its colour reads go through `MaterialTheme.colorScheme`, and exactly **two**
# did not. Replacing the theme changed 210 places and left two grey swatches in the poster
# studio looking like Material's old baseline. Two is a number somebody finds. Twenty is a
# redesign that does not land, and twenty is what two becomes over four more milestones unless
# something says no.
#
# ## Why it is a script and not a lint rule or a test
#
# Modelled on `tools/check-no-pii.sh`, deliberately, including the part that matters most:
#
#   **M9's lesson was that a guard inside a path-filtered workflow is blind to the case that
#   motivated it.** `no-pii-in-repo.spec.ts` grepped the whole tree and ran only when
#   `server/**` changed; the leak it was written for was in `docs/`. So this one runs from
#   `.github/workflows/design.yml` with **no path filter at all**, rather than being added to
#   `android.yml`, which is filtered to `composeApp/**` and friends.
#
# A detekt rule or a Compose lint check would be the idiomatic answer and would cost a toolchain,
# a configuration file and a Gradle task in a milestone that is otherwise adding none. This costs
# a checkout and under a second.
#
# ## What it looks for
#
#   Color(0xFF…)          an ARGB literal
#   Color(0.5f, …)        the float constructor
#   Color.Gray            a named Compose constant
#   android.graphics.Color  the platform one, by any route
#
# ## What it deliberately does NOT look for
#
#   - `Color.Transparent` and `Color.Unspecified`. Neither is a colour: one is the absence of
#     paint and the other is Compose's "inherit". Flagging them would teach people to switch the
#     check off, which is the real failure mode for a check like this one.
#   - Anything under `ui/theme/`, on either source set. That directory is the one place in this
#     app allowed to name a colour, which is the whole point of the rule.
#   - `res/values*/colors.xml`. The launcher icon's background and the splash window's background
#     have to be Android resources — a vector drawable and a window theme cannot read a Compose
#     `ColorScheme`. Those two files are `ui/theme/`'s equivalent on the resource side, and they
#     are small enough to read. Any OTHER XML naming a colour is still a hit.
#   - Test fixtures' *hex strings*, such as `PosterTheme`'s `backgroundFrom`. Those are data
#     from the server, not paint chosen here.
#
# ## The one escape hatch
#
# A line carrying `raw-color-ok:` followed by a reason is skipped. There are exactly two uses in
# the repository, and both are cases where the value is not paint this app chose:
#
#   1. `PosterScreen.parseHex` — an alpha mask OR'd onto a `#RRGGBB` that came off the wire from
#      `GET /v1/themes`. A parser, not a choice.
#   2. `LauncherIconTest` — a test that stands in for the SYSTEM, tinting the monochrome icon
#      the way a launcher tints it from the wallpaper palette. An Android drawable rendered
#      outside any composition cannot read `MaterialTheme`.
#
# **Test sources are deliberately in scope.** Excluding them would be the easy fix for the
# second case and the wrong one: a design milestone's own tests are exactly where a wrong colour
# gets laundered into evidence, and `M7_VERIFY.md`'s screenshots come out of this source set. A
# hatch with no reason is a hatch that gets used; requiring words after the colon is what keeps
# the count at two.
#
# Usage:
#   tools/check-no-raw-colors.sh            # scan; exit 1 and print file:line:match on a hit
#   tools/check-no-raw-colors.sh --pattern  # print the regex and exit, so one definition serves all

set -euo pipefail

PATTERN='Color\(0[xX]|Color\([0-9]|Color\.(Black|White|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b|android\.graphics\.Color|ColorFilter\.tint\(Color\.'

if [[ "${1:-}" == "--pattern" ]]; then
  printf '%s\n' "$PATTERN"
  exit 0
fi

cd "$(git rev-parse --show-toplevel)"

#
# `--untracked`, for the same reason `check-no-pii.sh` uses it: plain `git grep` searches TRACKED
# files only, so a brand-new screen would not be searched at all until after it was committed.
#
# The pathspecs exclude `ui/theme/` on every source set, the two resource files named above, and
# this script, which is about the pattern and therefore contains it.
#
hits="$(git grep -nIE --untracked "$PATTERN" -- \
  'composeApp/src/*.kt' \
  'composeApp/src/*.xml' \
  ':(exclude)composeApp/src/*/kotlin/in/wishkaro/app/ui/theme/*' \
  ':(exclude)composeApp/src/androidMain/res/values/colors.xml' \
  ':(exclude)composeApp/src/androidMain/res/values-night/colors.xml' \
  || true)"

# Drop the lines that carry a reasoned exemption. `grep -v` on the whole line, because the
# marker is a trailing comment on the offending line itself rather than a block above it —
# an exemption you cannot see while reading the code is an exemption nobody re-examines.
hits="$(printf '%s' "$hits" | grep -v 'raw-color-ok:' || true)"

if [[ -z "$hits" ]]; then
  echo "no-raw-colors: clean"
  exit 0
fi

#
# Unlike `check-no-pii.sh`, this one PRINTS the match. The thing it finds is a colour, which is
# not a secret and is useless without the file it came from — and the fix is nearly always
# obvious from seeing it. The PII check's silence is about the content; there is no equivalent
# reason here, and a guard that makes you open a file to learn what it objected to is a guard
# people route around.
#
echo "no-raw-colors: a colour is named outside composeApp/src/*/kotlin/in/wishkaro/app/ui/theme/." >&2
echo "" >&2
printf '%s\n' "$hits" | sed 's/^/  /' >&2
echo "" >&2
echo "Use MaterialTheme.colorScheme, or MaterialTheme.accent where the value is the occasion's" >&2
echo "accent and must desaturate on a remembrance (see ui/theme/Solemn.kt)." >&2
echo "" >&2
echo "If the value genuinely is not paint — a parser, a bit mask, a system tint — put" >&2
echo "'raw-color-ok: <reason>' ON THAT LINE. There are two in the repository; add a third" >&2
echo "only with a reason worth reading, and read the header here first." >&2
exit 1
