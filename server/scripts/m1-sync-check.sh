#!/bin/bash
# M1 sync walkthrough: traits, the widened fields, and the properties that must not regress.
# Requires a server on $U with ALLOW_DEV_LOGIN=true.
set -uo pipefail
U=${U:-http://127.0.0.1:3000}
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n     -> %s\n' "$1" "${2:-}"; fail=$((fail+1)); }
eq()  { [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected [$3] got [$2]"; }
uuid(){ uuidgen | tr 'A-Z' 'a-z'; }

TOK=$(curl -sS -X POST "$U/v1/auth/dev-token" -H 'content-type: application/json' \
      --data "{\"email\":\"sync-$(date +%s)@wishkaro.local\",\"name\":\"Sync Tester\"}" \
      | jq -r '.tokens.accessToken // .accessToken')
[ -n "$TOK" ] && [ "$TOK" != null ] || { echo "no session token"; exit 1; }
A=(-H "Authorization: Bearer $TOK" -H 'content-type: application/json')

PID=$(uuid); EID=$(uuid); TID=$(uuid); T2=$(uuid)
NOW=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)

echo "== 1. a batch of people, events and traits =="
BATCH=$(jq -nc --arg p "$PID" --arg e "$EID" --arg t "$TID" --arg t2 "$T2" --arg now "$NOW" '{
  people: [{id:$p, displayName:"Rohan Gupta", relationToOwner:"Colleague",
            gender:"male", phone:"+919812345678", notes:"allergic to peanuts", updatedAt:$now}],
  events: [{id:$e, personId:$p, type:"WEDDING_ANNIVERSARY", day:11, month:11, year:2016, updatedAt:$now}],
  traits: [{id:$t,  personId:$p, kind:"HOBBY", value:"trekking",  updatedAt:$now},
           {id:$t2, personId:$p, kind:"BUDGET_BAND", value:"1000-2500", updatedAt:$now}]
}')
R=$(curl -sS "${A[@]}" -X POST "$U/v1/sync" --data "$BATCH")
eq "1 person applied" "$(jq -r .applied.people <<<"$R")" "1"
eq "1 event applied"  "$(jq -r .applied.events <<<"$R")" "1"
eq "2 traits applied" "$(jq -r .applied.traits <<<"$R")" "2"
eq "nothing skipped"  "$(jq -r '.skipped|length' <<<"$R")" "0"

echo "== 2. the pull brings all three back, with the derived values =="
P=$(curl -sS "${A[@]}" "$U/v1/sync")
eq "person returned"          "$(jq -r --arg p "$PID" '[.people[]|select(.id==$p)]|length' <<<"$P")" "1"
eq "profile fields round-trip" "$(jq -r --arg p "$PID" '.people[]|select(.id==$p).notes' <<<"$P")" "allergic to peanuts"
eq "completeness came down derived" \
   "$([ "$(jq -r --arg p "$PID" '.people[]|select(.id==$p).completenessScore' <<<"$P")" -gt 0 ] && echo yes || echo no)" "yes"
eq "both traits returned"     "$(jq -r --arg p "$PID" '[.traits[]|select(.personId==$p)]|length' <<<"$P")" "2"
eq "trait source is USER"     "$(jq -r --arg p "$PID" '[.traits[]|select(.personId==$p)][0].source' <<<"$P")" "USER"
eq "event sentiment derived"  "$(jq -r --arg e "$EID" '.events[]|select(.id==$e).sentiment' <<<"$P")" "CELEBRATORY"
eq "isMuted came down"        "$(jq -r --arg e "$EID" '.events[]|select(.id==$e).isMuted' <<<"$P")" "false"

echo "== 3. replaying the batch is a no-op (idempotency) =="
R=$(curl -sS "${A[@]}" -X POST "$U/v1/sync" --data "$BATCH")
eq "nothing applied on replay" \
   "$(jq -r '.applied.people + .applied.events + .applied.traits' <<<"$R")" "0"
eq "all four skipped as STALE" "$(jq -r '[.skipped[]|select(.reason=="STALE")]|length' <<<"$R")" "4"

echo "== 4. derived fields are refused on the way up =="
for f in '"sentiment":"CELEBRATORY"' '"isMuted":true,"sentiment":"SOLEMN"'; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X POST "$U/v1/sync" \
    --data "{\"events\":[{\"id\":\"$(uuid)\",\"personId\":\"$PID\",\"type\":\"BIRTHDAY\",\"day\":1,\"month\":1,\"updatedAt\":\"$NOW\",$f}]}")
  eq "a pushed event carrying $f is refused" "$code" "400"
done
code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X POST "$U/v1/sync" \
  --data "{\"people\":[{\"id\":\"$(uuid)\",\"displayName\":\"X\",\"completenessScore\":99,\"updatedAt\":\"$NOW\"}]}")
eq "a pushed person carrying completenessScore is refused" "$code" "400"
code=$(curl -sS -o /dev/null -w '%{http_code}' "${A[@]}" -X POST "$U/v1/sync" \
  --data "{\"traits\":[{\"id\":\"$(uuid)\",\"personId\":\"$PID\",\"kind\":\"LIKE\",\"value\":\"x\",\"source\":\"INFERRED\",\"updatedAt\":\"$NOW\"}]}")
eq "a pushed trait claiming source=INFERRED is refused" "$code" "400"

echo "== 5. a death anniversary pushed through sync flips everything =="
LATER=$(date -u -v+1M +%Y-%m-%dT%H:%M:%S.000Z 2>/dev/null || date -u -d '+1 minute' +%Y-%m-%dT%H:%M:%S.000Z)
DID=$(uuid)
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/sync" --data "$(jq -nc --arg p "$PID" --arg d "$DID" --arg now "$LATER" '{
  events:[{id:$d, personId:$p, type:"DEATH_ANNIVERSARY", day:4, month:4, year:2019, updatedAt:$now}]}')"
P=$(curl -sS "${A[@]}" "$U/v1/sync")
eq "the person is deceased in the next pull" \
   "$(jq -r --arg p "$PID" '.people[]|select(.id==$p).isDeceased' <<<"$P")" "true"
eq "their existing anniversary is now SOLEMN" \
   "$(jq -r --arg e "$EID" '.events[]|select(.id==$e).sentiment' <<<"$P")" "SOLEMN"

echo "== 6. an absent isMuted must not un-mute (FR-EVT-05) =="
BID=$(uuid)
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/sync" --data "$(jq -nc --arg p "$PID" --arg b "$BID" --arg now "$LATER" '{
  events:[{id:$b, personId:$p, type:"BIRTHDAY", day:7, month:9, year:1980, updatedAt:$now}]}')"
MUTED=$(curl -sS "${A[@]}" "$U/v1/sync" | jq -r --arg b "$BID" '.events[]|select(.id==$b).isMuted')
eq "a deceased person's pushed birthday is auto-muted" "$MUTED" "true"
EVEN_LATER=$(date -u -v+2M +%Y-%m-%dT%H:%M:%S.000Z 2>/dev/null || date -u -d '+2 minutes' +%Y-%m-%dT%H:%M:%S.000Z)
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/sync" --data "$(jq -nc --arg p "$PID" --arg b "$BID" --arg now "$EVEN_LATER" '{
  events:[{id:$b, personId:$p, type:"BIRTHDAY", day:7, month:9, year:1980, updatedAt:$now}]}')"
eq "re-pushing it without isMuted leaves it muted" \
   "$(curl -sS "${A[@]}" "$U/v1/sync" | jq -r --arg b "$BID" '.events[]|select(.id==$b).isMuted')" "true"

echo "== 7. trait tombstones sync =="
GONE=$(date -u -v+3M +%Y-%m-%dT%H:%M:%S.000Z 2>/dev/null || date -u -d '+3 minutes' +%Y-%m-%dT%H:%M:%S.000Z)
curl -sS -o /dev/null "${A[@]}" -X POST "$U/v1/sync" --data "$(jq -nc --arg p "$PID" --arg t "$TID" --arg now "$GONE" '{
  traits:[{id:$t, personId:$p, kind:"HOBBY", value:"trekking", updatedAt:$now, deletedAt:$now}]}')"
eq "the tombstone comes back on pull, not an absence" \
   "$(curl -sS "${A[@]}" "$U/v1/sync" | jq -r --arg t "$TID" '[.traits[]|select(.id==$t and .deletedAt!=null)]|length')" "1"

echo "== 8. an M0-shaped cursor still works =="
M0CUR=$(printf '{"person":null,"event":null}' | base64 | tr -d '=\n' | tr '+/' '-_')
eq "a cursor with no trait key is accepted and resyncs traits" \
   "$([ "$(curl -sS "${A[@]}" "$U/v1/sync?cursor=$M0CUR" | jq -r '.traits|length')" -ge 1 ] && echo yes || echo no)" "yes"

echo
echo "================  $pass passed, $fail failed  ================"
[ "$fail" -eq 0 ]
