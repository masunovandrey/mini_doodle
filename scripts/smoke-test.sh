#!/usr/bin/env bash
#
# Black-box smoke test for a running Mini Doodle deployment (e.g. `docker compose up --build`).
# Covers the full user -> slot -> meeting -> availability flow plus a documented error case.
#
# Usage:
#   scripts/smoke-test.sh                 # targets http://localhost:8080
#   BASE_URL=http://host:port scripts/smoke-test.sh
#
# Requires: curl, python3.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="$(date +%s)${RANDOM}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

say()  { printf '\n== %s\n' "$*"; }
pass() { printf 'PASS: %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

status() {
    curl -sS -o "$tmp/body" -D "$tmp/headers" -w '%{http_code}' "$@"
}

field() {
    python3 - "$tmp/body" "$1" <<'PY'
import json, sys
node = json.load(open(sys.argv[1]))
for key in sys.argv[2].split('.'):
    node = node[int(key)] if key.isdigit() else node[key]
print(node)
PY
}

etag() {
    python3 - "$tmp/headers" <<'PY'
import sys
for line in open(sys.argv[1]):
    name, _, value = line.partition(':')
    if name.strip().lower() == 'etag':
        print(value.strip())
        break
PY
}

say "Create user"
code=$(status -X POST "$BASE_URL/users" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"smoke-${RUN_ID}@example.com\",\"name\":\"Smoke User ${RUN_ID}\"}")
[[ "$code" == "201" ]] || fail "POST /users expected 201, got $code: $(cat "$tmp/body")"
user_id=$(field id)
calendar_id=$(field calendarId)
pass "POST /users 201 (user=$user_id calendar=$calendar_id)"

say "Get user"
code=$(status "$BASE_URL/users/$user_id")
[[ "$code" == "200" ]] || fail "GET /users/$user_id expected 200, got $code: $(cat "$tmp/body")"
pass "GET /users/{userId} 200"

say "Create slot"
code=$(status -X POST "$BASE_URL/calendars/$calendar_id/slots" \
    -H 'Content-Type: application/json' \
    -d '{"start":"2035-01-05T09:00:00Z","end":"2035-01-05T10:30:00Z"}')
[[ "$code" == "201" ]] || fail "POST slots expected 201, got $code: $(cat "$tmp/body")"
slot_id=$(field id)
slot_etag=$(etag)
[[ -n "$slot_etag" ]] || fail "POST slots did not return an ETag"
pass "POST slots 201 (slot=$slot_id etag=$slot_etag)"

say "Convert slot to meeting"
code=$(status -X POST "$BASE_URL/calendars/$calendar_id/slots/$slot_id/meeting" \
    -H 'Content-Type: application/json' \
    -H "If-Match: $slot_etag" \
    -d '{"title":"Smoke kickoff","description":"black-box smoke flow","participants":["ada@example.com"]}')
[[ "$code" == "201" ]] || fail "POST meeting expected 201, got $code: $(cat "$tmp/body")"
[[ "$(field slotState)" == "BUSY" ]] || fail "converted slot should be BUSY: $(cat "$tmp/body")"
pass "POST meeting 201 (slot is BUSY)"

say "Query availability"
code=$(status "$BASE_URL/calendars/$calendar_id/availability?start=2035-01-05T09:00:00Z&end=2035-01-05T11:00:00Z")
[[ "$code" == "200" ]] || fail "GET availability expected 200, got $code: $(cat "$tmp/body")"
python3 - "$tmp/body" <<'PY' || fail "availability did not report the BUSY interval"
import json, sys
doc = json.load(open(sys.argv[1]))
busy = [i for i in doc["intervals"] if i["state"] == "BUSY"]
assert busy, doc
assert busy[0]["start"].startswith("2035-01-05T09:00:00"), busy
assert busy[0]["end"].startswith("2035-01-05T10:30:00"), busy
PY
pass "GET availability 200 (BUSY interval exposed)"

say "Meeting-backed slot cannot be deleted"
code=$(status -X DELETE "$BASE_URL/calendars/$calendar_id/slots/$slot_id" -H "If-Match: $slot_etag")
[[ "$code" == "409" ]] || fail "DELETE meeting-backed slot expected 409, got $code: $(cat "$tmp/body")"
pass "DELETE meeting-backed slot 409 (RFC 9457 Problem Details)"

printf '\nAll smoke checks passed against %s\n' "$BASE_URL"
