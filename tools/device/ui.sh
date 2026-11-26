#!/usr/bin/env bash
#
# Drive the app on a device WITHOUT guessing coordinates.
#
# This exists because three times across M2 and M3 a blind `adb shell input tap` went
# somewhere it should not have: into FloBooks, into a Slack reply box, and onto a different
# real person's record with a text field focused. MILESTONE_CHECKLIST.md §E grew a guard rail
# after each one, and all of them check the state BEFORE the tap. None of them can tell where
# the tap landed.
#
# So this script does two things differently:
#
#   1. It never taps a coordinate read off a screenshot. It dumps the accessibility tree,
#      finds the node by id or text, and taps the centre of ITS bounds. Zero matches or more
#      than one is a hard failure, not a best guess.
#   2. Every `tap` is meant to be followed by an `expect`, which dumps again and asserts.
#      That is the half that did not exist: a tap that lands in another app FAILS THE STEP
#      instead of being typed into.
#
# Compose nodes appear in that tree as `resource-id` only because MainActivity sets
# `testTagsAsResourceId = true` on the root. Anything you want to address needs a
# `Modifier.testTag("...")` — see docs/M4_PLAN.md §4.
#
# Usage:
#   tools/device/ui.sh guard                 focused window + fatal count + screenshot
#   tools/device/ui.sh tap    <selector>     guard, resolve, tap the node's centre
#   tools/device/ui.sh expect <selector>     assert the node is present (use after every tap)
#   tools/device/ui.sh absent <selector>     assert the node is NOT present
#   tools/device/ui.sh type   <selector> <text>   tap the field, confirm it took focus, type
#   tools/device/ui.sh dump                  print the resolved tree, for writing selectors
#   tools/device/ui.sh shot   [name]         screenshot only
#
# Selectors:
#   id:people_fab      resource-id, matched on the part after "/" or in full
#   text:Add person    visible text, exact
#   desc:Add person    content-description, exact
#   people_fab         bare: tries id, then text, then desc
#
# Env:
#   ANDROID_SERIAL   device, when more than one is attached
#   WK_PKG           package, default in.wishkaro.app
#   WK_ACT           expected focused activity, default $WK_PKG/.MainActivity
#   WK_SHOTS         where screenshots go, default ./build/device-shots

set -euo pipefail

PKG="${WK_PKG:-in.wishkaro.app}"
ACT="${WK_ACT:-$PKG/.MainActivity}"
SHOTS="${WK_SHOTS:-build/device-shots}"

die() { printf '\033[31mFAIL\033[0m  %s\n' "$*" >&2; exit 1; }
ok()  { printf '\033[32mok\033[0m    %s\n' "$*" >&2; }
note(){ printf '      %s\n' "$*" >&2; }

need_adb() {
  command -v adb >/dev/null || die "adb is not on PATH"
  local n
  n="$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
  [ "$n" = "0" ] && die "no device attached"
  [ "$n" != "1" ] && [ -z "${ANDROID_SERIAL:-}" ] && die "$n devices attached; set ANDROID_SERIAL"
  return 0
}

# §E, and M3's addition: a LOCKED phone looks exactly like a working one to `am start`.
# mCurrentFocus naming NotificationShade or a Keyguard window means locked, not broken.
guard() {
  need_adb
  local focus
  focus="$(adb shell dumpsys window | grep -m1 mCurrentFocus || true)"
  note "focus: ${focus:-<none>}"

  case "$focus" in
    *Keyguard*|*NotificationShade*|*StatusBar*)
      die "the device is LOCKED or the shade is open — unlock it. ($focus)" ;;
  esac

  grep -q "$ACT" <<<"$focus" \
    || die "focused window is not $ACT — refusing to send input. ($focus)"

  local fatals
  fatals="$(adb logcat -d -t 400 | grep -c 'FATAL EXCEPTION' || true)"
  [ "$fatals" = "0" ] || die "$fatals FATAL EXCEPTION in the last 400 log lines"

  ok "focused on $ACT, 0 fatals"
}

# The accessibility tree, as XML on stdout. Never written to the device's storage.
dump_xml() {
  adb exec-out uiautomator dump /dev/tty 2>/dev/null \
    | sed 's/UI hierchary dumped to: \/dev\/tty//' \
    | tr -d '\r'
}

# Resolve a selector to exactly one node. Prints "x y  <description>".
# Zero matches or more than one is a failure: this is the whole point of the script.
resolve() {
  local selector="$1"
  dump_xml | WK_SELECTOR="$selector" python3 -c '
import os, sys, re
import xml.etree.ElementTree as ET

sel = os.environ["WK_SELECTOR"]
kind, _, value = sel.partition(":")
if kind not in ("id", "text", "desc"):
    kind, value = None, sel          # bare selector: try all three, in order

raw = sys.stdin.read()
start = raw.find("<?xml")
if start < 0:
    start = raw.find("<hierarchy")
if start < 0:
    sys.exit("could not find an XML document in the uiautomator output")
try:
    root = ET.fromstring(raw[start:])
except ET.ParseError as e:
    sys.exit(f"could not parse the uiautomator dump: {e}")

nodes = list(root.iter("node"))

def matches(n, kind, value):
    if kind == "id":
        rid = n.get("resource-id", "")
        return rid == value or rid.rsplit("/", 1)[-1] == value
    if kind == "text":
        return n.get("text", "") == value
    if kind == "desc":
        return n.get("content-desc", "") == value
    return False

kinds = [kind] if kind else ["id", "text", "desc"]
found, used = [], None
for k in kinds:
    found = [n for n in nodes if matches(n, k, value)]
    if found:
        used = k
        break

def describe(n):
    bits = [f"{a}={n.get(a)!r}" for a in ("resource-id", "text", "content-desc", "class")
            if n.get(a)]
    return "  ".join(bits)

if not found:
    print(f"no node matches {sel!r}.", file=sys.stderr)
    print("visible ids:", file=sys.stderr)
    seen = sorted({n.get("resource-id", "").rsplit("/", 1)[-1]
                   for n in nodes if n.get("resource-id")})
    for s in seen:
        print(f"    id:{s}", file=sys.stderr)
    print("visible text:", file=sys.stderr)
    for t in sorted({n.get("text", "") for n in nodes if n.get("text")}):
        print(f"    text:{t}", file=sys.stderr)
    sys.exit(1)

if len(found) > 1:
    print(f"{sel!r} matches {len(found)} nodes as {used}. Refusing to guess:", file=sys.stderr)
    for n in found:
        print("    " + describe(n), file=sys.stderr)
    sys.exit(1)

n = found[0]
if n.get("enabled") == "false":
    sys.exit(f"{sel!r} resolved to a DISABLED node: {describe(n)}")

m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
if not m:
    sys.exit(f"{sel!r} resolved to a node with no usable bounds: {describe(n)}")
x1, y1, x2, y2 = map(int, m.groups())
if x2 <= x1 or y2 <= y1:
    sys.exit(f"{sel!r} resolved to a zero-area node: {describe(n)}")

print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}  {describe(n)}")
'
}

shot() {
  need_adb
  mkdir -p "$SHOTS"
  local name="${1:-shot}-$(date +%H%M%S).png"
  adb exec-out screencap -p > "$SHOTS/$name"
  local bytes
  bytes="$(wc -c < "$SHOTS/$name" | tr -d ' ')"
  [ "$bytes" -gt 1000 ] || die "screencap produced $bytes bytes — look at the device"
  ok "screenshot $SHOTS/$name ($bytes bytes)"
  echo "$SHOTS/$name"
}

cmd_tap() {
  local selector="$1"
  guard
  local line x y desc
  line="$(resolve "$selector")" || die "could not resolve $selector"
  x="$(awk '{print $1}' <<<"$line")"
  y="$(awk '{print $2}' <<<"$line")"
  desc="$(cut -d' ' -f3- <<<"$line")"
  note "tapping ($x,$y) = $desc"
  adb shell input tap "$x" "$y"
  ok "tapped $selector"
}

cmd_expect() {
  local selector="$1"
  local line
  line="$(resolve "$selector")" || die "expected $selector after the last action; it is not on screen"
  ok "present: $selector"
}

cmd_absent() {
  local selector="$1"
  if resolve "$selector" >/dev/null 2>&1; then
    die "expected $selector to be GONE; it is still on screen"
  fi
  ok "absent: $selector"
}

# Typing is where one of the three incidents did its damage, so the field must prove it has
# focus before a single character is sent.
cmd_type() {
  local selector="$1" text="$2"
  cmd_tap "$selector"
  local focused
  focused="$(dump_xml | WK_SELECTOR="$selector" python3 -c '
import os, sys
import xml.etree.ElementTree as ET
sel = os.environ["WK_SELECTOR"]; kind, _, value = sel.partition(":")
if kind not in ("id","text","desc"): kind, value = "id", sel
raw = sys.stdin.read(); s = raw.find("<?xml")
if s < 0: s = raw.find("<hierarchy")
root = ET.fromstring(raw[s:])
for n in root.iter("node"):
    rid = n.get("resource-id","")
    hit = (rid == value or rid.rsplit("/",1)[-1] == value) if kind=="id" else \
          (n.get("text","") == value if kind=="text" else n.get("content-desc","") == value)
    if hit:
        print("yes" if n.get("focused") == "true" else "no"); break
else:
    print("gone")
')"
  [ "$focused" = "yes" ] || die "$selector did not take focus (got: $focused) — NOT typing"
  adb shell input text "$(sed 's/ /%s/g' <<<"$text")"
  ok "typed into $selector"
}

cmd_dump() {
  need_adb
  dump_xml | python3 -c '
import sys, xml.etree.ElementTree as ET
raw = sys.stdin.read(); s = raw.find("<?xml")
if s < 0: s = raw.find("<hierarchy")
root = ET.fromstring(raw[s:])
for n in root.iter("node"):
    bits = [f"{a}={n.get(a)!r}" for a in ("resource-id","text","content-desc")
            if n.get(a)]
    if bits:
        print(n.get("bounds",""), "  ".join(bits))
'
}

case "${1:-}" in
  guard)  guard ;;
  tap)    [ $# -ge 2 ] || die "tap needs a selector";    cmd_tap    "$2" ;;
  expect) [ $# -ge 2 ] || die "expect needs a selector"; cmd_expect "$2" ;;
  absent) [ $# -ge 2 ] || die "absent needs a selector"; cmd_absent "$2" ;;
  type)   [ $# -ge 3 ] || die "type needs a selector and text"; cmd_type "$2" "$3" ;;
  dump)   cmd_dump ;;
  shot)   shot "${2:-shot}" ;;
  *)      sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
