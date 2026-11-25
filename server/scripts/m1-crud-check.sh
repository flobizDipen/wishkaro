#!/bin/bash
# M1 server CRUD walkthrough. Every assertion prints PASS or FAIL.
set -uo pipefail
U=http://127.0.0.1:3000
pass=0; fail=0
ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  FAIL  %s\n     -> %s\n' "$1" "${2:-}"; fail=$((fail+1)); }
eq()   { [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected [$3] got [$2]"; }

# --- session -------------------------------------------------------------------------
EMAIL="m1-$(date +%s)@wishkaro.local"
TOK=$(curl -sS -X POST "$U/v1/auth/dev-token" -H 'content-type: application/json' \
      --data "{\"email\":\"$EMAIL\",\"name\":\"M1 Tester\"}" | jq -r '.tokens.accessToken // .accessToken')
[ -n "$TOK" ] && [ "$TOK" != null ] || { echo "no session token"; exit 1; }
A=(-H "Authorization: Bearer $TOK" -H 'content-type: application/json')

echo "== 1. derived values are rejected, not stripped =="
code=$(curl -sS -o /tmp/r1 -w '%{http_code}' "${A[@]}" -X POST "$U/v1/people" \
  --data '{"displayName":"Sneaky","event":{"type":"DEATH_ANNIVERSARY","day":3,"month":4,"year":2010,"sentiment":"CELEBRATORY"}}')
eq "client sentiment=CELEBRATORY on a DEATH_ANNIVERSARY is refused" "$code" "400"
jq -e '.error.details[]?|select(.message|test("sentiment"))' /tmp/r1 >/dev/null 2>&1 \
  && ok "the 400 names the offending field" || bad "the 400 names the offending field" "$(head -c 200 /tmp/r1)"

for f in isMinor completenessScore milestoneLabel; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X POST "$U/v1/people" --data "{\"displayName\":\"X\",\"$f\":5}")
  eq "client-sent $f is refused" "$code" "400"
done

echo "== 2. a person with several events =="
P=$(curl -sS "${A[@]}" -X POST "$U/v1/people" \
    --data '{"displayName":"Aarav Mehta","relationToOwner":"Brother","event":{"type":"BIRTHDAY","day":14,"month":3,"year":1990}}')
PID=$(jq -r .id <<<"$P")
eq "person created"                "$(jq -r .displayName <<<"$P")" "Aarav Mehta"
eq "birthday is CELEBRATORY"       "$(jq -r '.events[0].sentiment' <<<"$P")" "CELEBRATORY"
eq "not deceased"                  "$(jq -r .isDeceased <<<"$P")" "false"
eq "not a minor"                   "$(jq -r .isMinor <<<"$P")" "false"

curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/events" \
  --data "{\"personId\":\"$PID\",\"type\":\"WEDDING_ANNIVERSARY\",\"day\":2,\"month\":12,\"year\":2001}"
P=$(curl -sS "${A[@]}" "$U/v1/people/$PID")
eq "two events on one person"      "$(jq -r '.events|length' <<<"$P")" "2"
eq "25th Silver derived"           "$(jq -r '.events[]|select(.type=="WEDDING_ANNIVERSARY").milestoneLabel' <<<"$P")" "25th Silver"

echo "== 3. CUSTOM events and labels =="
code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X POST "$U/v1/events" \
  --data "{\"personId\":\"$PID\",\"type\":\"BIRTHDAY\",\"day\":1,\"month\":6,\"label\":\"nope\"}")
eq "label on a BIRTHDAY is refused" "$code" "400"
code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X POST "$U/v1/events" \
  --data "{\"personId\":\"$PID\",\"type\":\"CUSTOM\",\"day\":1,\"month\":6}")
eq "CUSTOM without a label is refused" "$code" "400"
CID=$(curl -sS "${A[@]}" -X POST "$U/v1/events" \
  --data "{\"personId\":\"$PID\",\"type\":\"CUSTOM\",\"day\":1,\"month\":6,\"label\":\"Shop anniversary\"}" | jq -r .id)
[ -n "$CID" ] && ok "CUSTOM with a label is created" || bad "CUSTOM with a label is created"

echo "== 4. profile depth and completeness =="
before=$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r .completenessScore)
curl -sS -o /dev/null "${A[@]}" -X PATCH "$U/v1/people/$PID" \
  --data '{"gender":"male","phone":"+919876543210","notes":"filter coffee, not instant"}'
mid=$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r .completenessScore)
[ "$mid" -gt "$before" ] && ok "completeness rises after a PATCH ($before -> $mid)" || bad "completeness rises" "$before -> $mid"
eq "heaviest missing field is asked for first" \
   "$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r '.completenessSuggestions[0]')" "hobby"
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/people/$PID/traits" --data '{"kind":"HOBBY","value":"cricket"}'
after=$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r .completenessScore)
[ "$after" -gt "$mid" ] && ok "a trait raises completeness ($mid -> $after)" || bad "a trait raises completeness"

echo "== 5. PATCH leaves untouched fields alone =="
curl -sS -o /dev/null "${A[@]}" -X PATCH "$U/v1/people/$PID" --data '{"email":"aarav@example.com"}'
eq "notes survived an unrelated PATCH" \
   "$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r .notes)" "filter coffee, not instant"
curl -sS -o /dev/null "${A[@]}" -X PATCH "$U/v1/people/$PID" --data '{"notes":null}'
eq "explicit null clears a field" \
   "$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r .notes)" "null"

echo "== 6. search and filter (FR-PPL-10) =="
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/people" \
  --data '{"displayName":"Meera Nair","relationToOwner":"Friend","event":{"type":"BIRTHDAY","day":29,"month":2}}'
eq "search by name"     "$(curl -sS "${A[@]}" "$U/v1/people?q=meera" | jq -r 'length')" "1"
eq "search is case-insensitive" "$(curl -sS "${A[@]}" "$U/v1/people?q=MEHTA" | jq -r 'length')" "1"
eq "filter by relation" "$(curl -sS "${A[@]}" "$U/v1/people?relation=Friend" | jq -r '.[0].displayName')" "Meera Nair"
eq "filter by month"    "$(curl -sS "${A[@]}" "$U/v1/people?month=12" | jq -r '.[0].displayName')" "Aarav Mehta"
eq "relations chip list is served" "$(curl -sS "${A[@]}" "$U/v1/people/relations" | jq -r '.relations|length')" "17"

echo "== 7. duplicate detection (FR-PPL-06) =="
eq "reordered name + same date is flagged" \
   "$(curl -sS "${A[@]}" "$U/v1/people/duplicates?displayName=Mehta%20Aarav&day=14&month=3" | jq -r 'length')" "1"
eq "same name, different date is not" \
   "$(curl -sS "${A[@]}" "$U/v1/people/duplicates?displayName=Mehta%20Aarav&day=15&month=3" | jq -r 'length')" "0"
eq "an unrelated name is not" \
   "$(curl -sS "${A[@]}" "$U/v1/people/duplicates?displayName=Rohan%20Gupta&day=14&month=3" | jq -r 'length')" "0"

echo "== 8. year unknown stays unknown (FR-EVT-02) =="
M=$(curl -sS "${A[@]}" "$U/v1/people?q=meera" | jq '.[0]')
eq "no age for an unknown year"       "$(jq -r '.events[0].turningAge' <<<"$M")" "null"
eq "no milestone for an unknown year" "$(jq -r '.events[0].milestoneLabel' <<<"$M")" "null"
eq "29 Feb still resolves"            "$(jq -r '.events[0].nextOccurrenceOn' <<<"$M" | cut -c6-)" "02-28"

echo "== 9. the keystone: a death anniversary changes everything (FR-EVT-04/05) =="
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/events" \
  --data "{\"personId\":\"$PID\",\"type\":\"DEATH_ANNIVERSARY\",\"day\":9,\"month\":8,\"year\":2014}"
P=$(curl -sS "${A[@]}" "$U/v1/people/$PID")
eq "person is now deceased" "$(jq -r .isDeceased <<<"$P")" "true"
eq "EVERY event is SOLEMN"  "$(jq -r '[.events[]|select(.sentiment!="SOLEMN")]|length' <<<"$P")" "0"
eq "the birthday is muted by default" \
   "$(jq -r '.events[]|select(.type=="BIRTHDAY").isMuted' <<<"$P")" "true"
eq "no celebratory milestone survives" \
   "$(jq -r '[.events[]|select(.milestoneLabel//""|test("Silver|Golden|st$|th$"))]|length' <<<"$P")" "0"
# The label describes the NEXT occurrence, as the wedding's "25th Silver" does: a death on
# 9 Aug 2014 whose next anniversary falls in 2027 is "13 years", not 12.
eq "the death anniversary gets a plain year count" \
   "$(jq -r '.events[]|select(.type=="DEATH_ANNIVERSARY").milestoneLabel' <<<"$P")" "13 years"
eq "no event is 'turning' an age any more" \
   "$(jq -r '[.events[]|select(.turningAge!=null)]|length' <<<"$P")" "0"
code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X PATCH "$U/v1/people/$PID" --data '{"isDeceased":false}')
eq "cannot un-mark deceased while a death anniversary is live" "$code" "400"

BID=$(jq -r '.events[]|select(.type=="BIRTHDAY").id' <<<"$P")
curl -sS -o /dev/null "${A[@]}" -X PATCH "$U/v1/events/$BID" --data '{"isMuted":false}'
P=$(curl -sS "${A[@]}" "$U/v1/people/$PID")
eq "un-muting opts in to a remembrance birthday" \
   "$(jq -r '.events[]|select(.type=="BIRTHDAY").isMuted' <<<"$P")" "false"
eq "and it is still SOLEMN" \
   "$(jq -r '.events[]|select(.type=="BIRTHDAY").sentiment' <<<"$P")" "SOLEMN"

echo "== 10. minors are excluded from profiling (FR-PPL-09) =="
KID=$(curl -sS "${A[@]}" -X POST "$U/v1/people" \
      --data '{"displayName":"Ananya Child","relationToOwner":"Cousin","event":{"type":"BIRTHDAY","day":5,"month":5,"year":2015}}')
KIDID=$(jq -r .id <<<"$KID")
eq "a child is flagged a minor"     "$(jq -r .isMinor <<<"$KID")" "true"
eq "nothing is asked about a minor" "$(jq -r '.completenessSuggestions|length' <<<"$KID")" "0"
code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X POST "$U/v1/people/$KIDID/traits" --data '{"kind":"LIKE","value":"dinosaurs"}')
eq "trait collection on a minor is refused" "$code" "400"

echo "== 11. soft delete, restore, and reminders stopping immediately (FR-PPL-08) =="
curl -sS -o /dev/null "${A[@]}" -X DELETE "$U/v1/people/$KIDID"
eq "deleted person leaves the list"     "$(curl -sS "${A[@]}" "$U/v1/people?q=ananya" | jq -r length)" "0"
eq "and appears in the restore window"  "$(curl -sS "${A[@]}" "$U/v1/people/deleted" | jq -r '[.[]|select(.id=="'"$KIDID"'")]|length')" "1"
eq "with 30 days left"                  "$(curl -sS "${A[@]}" "$U/v1/people/deleted" | jq -r '[.[]|select(.id=="'"$KIDID"'")][0].restorableForDays')" "30"
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/people/$KIDID/restore"
R=$(curl -sS "${A[@]}" "$U/v1/people?q=ananya")
eq "restored"                           "$(jq -r length <<<"$R")" "1"
eq "with their event intact"            "$(jq -r '.[0].events|length' <<<"$R")" "1"

echo "== 12. event delete and restore =="
curl -sS -o /dev/null "${A[@]}" -X DELETE "$U/v1/events/$CID"
eq "event removed"  "$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r '[.events[]|select(.id=="'"$CID"'")]|length')" "0"
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/events/$CID/restore"
eq "event restored" "$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r '[.events[]|select(.id=="'"$CID"'")]|length')" "1"

echo "== 13. per-occurrence override (FR-EVT-10) =="
WID=$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r '.events[]|select(.type=="WEDDING_ANNIVERSARY").id')
YEAR=$(curl -sS "${A[@]}" "$U/v1/people/$PID" | jq -r '.events[]|select(.type=="WEDDING_ANNIVERSARY").nextOccurrenceOn' | cut -d- -f1)
OUT=$(curl -sS "${A[@]}" -X PUT "$U/v1/events/$WID/occurrences/$YEAR/override" --data '{"overrideDate":"2026-12-06"}')
eq "override accepted" "$(jq -r .overrideDate <<<"$OUT")" "2026-12-06"
code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X PUT "$U/v1/events/$WID/occurrences/$YEAR/override" --data '{"overrideDate":"2026-02-31"}')
eq "an impossible override date is refused" "$code" "400"
OUT=$(curl -sS "${A[@]}" -X PUT "$U/v1/events/$WID/occurrences/$YEAR/override" --data '{"overrideDate":null}')
eq "override cleared" "$(jq -r .overrideDate <<<"$OUT")" "null"

echo "== 14. reminders stop IMMEDIATELY, not at the next tick (FR-PPL-08) =="
# The only assertions that need the database: reminder status is not exposed by the API, and
# "the reminder stops now" is precisely the claim a 200 response cannot make.
if command -v docker >/dev/null && docker exec wishkaro-db-1 true 2>/dev/null; then
  rstatus() {
    docker exec wishkaro-db-1 psql -U wishkaro -d wishkaro -tAc       "SELECT r.status FROM reminder r JOIN event_occurrence o ON o.id=r.event_occurrence_id WHERE o.event_id='$1';"       | tr -d '[:space:]'
  }
  MP=$(curl -sS "${A[@]}" -X POST "$U/v1/people"        --data '{"displayName":"Mute Target","relationToOwner":"Friend","event":{"type":"BIRTHDAY","day":1,"month":7,"year":1995}}')
  MPID=$(jq -r .id <<<"$MP"); MEID=$(jq -r '.events[0].id' <<<"$MP")

  eq "a new birthday is scheduled"        "$(rstatus "$MEID")" "PENDING"
  curl -sS -o /dev/null "${A[@]}" -X PATCH "$U/v1/events/$MEID" --data '{"isMuted":true}'
  eq "muting suppresses it at once"       "$(rstatus "$MEID")" "SUPPRESSED"
  curl -sS -o /dev/null "${A[@]}" -X PATCH "$U/v1/events/$MEID" --data '{"isMuted":false}'
  eq "un-muting brings it back"           "$(rstatus "$MEID")" "PENDING"
  curl -sS -o /dev/null "${A[@]}" -X DELETE "$U/v1/people/$MPID"
  eq "deleting the person suppresses it"  "$(rstatus "$MEID")" "SUPPRESSED"
else
  echo "  SKIP  reminder-status assertions (local Postgres container not reachable)"
fi

echo
echo "================  $pass passed, $fail failed  ================"
[ "$fail" -eq 0 ]
