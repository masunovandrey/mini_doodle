#!/usr/bin/env python3
"""Seeds representative benchmark data through the public HTTP API.

Creates NUM_CALENDARS users (one personal calendar each) and SLOTS_PER_CALENDAR
non-overlapping slots, marking every BUSY_EVERY-th slot BUSY. Results plus a
manifest consumed by the k6 scenarios are written to bench-data.json.

Deterministic by default: a fixed SEED_TAG yields identical emails and slot
layout, so runs are reproducible. Seeding is idempotent only within one clean
database; re-seed with the same tag after `docker compose down -v`.

Usage:
    python3 scripts/benchmark/seed.py
    SEED_TAG=bench002 NUM_CALENDARS=100 SLOTS_PER_CALENDAR=50 python3 scripts/benchmark/seed.py
    BENCH_DATA_OUT=/tmp/bench-data.json python3 scripts/benchmark/seed.py
"""

import http.client
import json
import os
import sys
import time
import urllib.parse
from datetime import datetime, timedelta, timezone

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
SEED_TAG = os.environ.get("SEED_TAG", "bench001")
NUM_CALENDARS = int(os.environ.get("NUM_CALENDARS", "200"))
SLOTS_PER_CALENDAR = int(os.environ.get("SLOTS_PER_CALENDAR", "50"))
BUSY_EVERY = int(os.environ.get("BUSY_EVERY", "5"))
OUT_PATH = os.environ.get(
    "BENCH_DATA_OUT",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench-data.json"),
)

READ_WINDOW = {"start": "2035-02-01T08:00:00Z", "end": "2035-03-15T18:00:00Z"}

parsed = urllib.parse.urlparse(BASE_URL)
HOST = parsed.hostname or "localhost"
PORT = parsed.port or 80

STD_PROFILE = "standard"
SPARSE_PROFILE = "sparse"


def iso(instant):
    return instant.isoformat().replace("+00:00", "Z")


def standard_slots():
    """SLOTS_PER_CALENDAR x [2h slot, 2h gap] starting 2035-02-01T08:00Z."""
    base = datetime(2035, 2, 1, 8, 0, tzinfo=timezone.utc)
    return [
        (
            iso(base + timedelta(hours=4 * i)),
            iso(base + timedelta(hours=4 * i + 2)),
        )
        for i in range(SLOTS_PER_CALENDAR)
    ]


def sparse_slots():
    """10 x [4h slot] starting one per day on 2035-02-01."""
    base = datetime(2035, 2, 1, 8, 0, tzinfo=timezone.utc)
    return [
        (iso(base + timedelta(hours=24 * i)), iso(base + timedelta(hours=24 * i + 4)))
        for i in range(10)
    ]


def profile_for(calendar_index):
    # Two representative layouts: ~80% dense calendars, ~20% sparse calendars.
    return STD_PROFILE if calendar_index < int(NUM_CALENDARS * 0.8) else SPARSE_PROFILE


def slot_layout(profile):
    return standard_slots() if profile == STD_PROFILE else sparse_slots()


class Client:
    def __init__(self):
        self.conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
        self.requests = 0

    def request(self, method, path, body=None, headers=None):
        self.requests += 1
        merged = dict(headers or {})
        payload = None
        if body is not None:
            merged.setdefault("Content-Type", "application/json")
            payload = json.dumps(body)
        self.conn.request(method, path, body=payload, headers=merged)
        response = self.conn.getresponse()
        raw = response.read()
        try:
            data = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            data = raw.decode("utf-8", "replace") if raw else None
        etag = response.getheader("ETag")
        return response.status, data, etag

    def expect(self, method, path, body, status, headers=None):
        actual, data, etag = self.request(method, path, body, headers)
        if actual != status:
            raise RuntimeError(f"{method} {path} expected {status}, got {actual}: {data!r}")
        return data, etag


def main():
    started = time.monotonic()
    client = Client()
    calendars = []
    total_slots = 0
    busy = 0
    contended = None

    for i in range(NUM_CALENDARS):
        user, _ = client.expect(
            "POST",
            "/users",
            {
                "email": f"bench-{SEED_TAG}-{i:04d}@example.com",
                "name": f"Bench User {SEED_TAG} {i:04d}",
            },
            201,
        )
        calendar_id = user["calendarId"]
        profile = profile_for(i)
        calendars.append({"id": calendar_id, "profile": profile})

        for j, (start, end) in enumerate(slot_layout(profile)):
            slot, etag = client.expect(
                "POST",
                f"/calendars/{calendar_id}/slots",
                {"start": start, "end": end},
                201,
            )
            total_slots += 1
            if i == 0 and contended is None:
                # Contended slot: the first FREE slot of the first calendar,
                # left unpatched so its ETag stays version "0".
                contended = {"calendarId": calendar_id, "slotId": slot["id"], "etag": etag}
            if (j + 1) % BUSY_EVERY == 0:
                client.expect(
                    "PATCH",
                    f"/calendars/{calendar_id}/slots/{slot['id']}",
                    {"state": "BUSY"},
                    200,
                    headers={"If-Match": etag},
                )
                busy += 1

    duration = time.monotonic() - started
    manifest = {
        "seedTag": SEED_TAG,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "readWindow": READ_WINDOW,
        "summary": {
            "users": NUM_CALENDARS,
            "calendars": NUM_CALENDARS,
            "slots": total_slots,
            "busy": busy,
            "free": total_slots - busy,
            "durationSeconds": round(duration, 2),
            "requests": client.requests,
            "requestsPerSecond": round(client.requests / duration, 1),
        },
        "contended": contended,
        "calendars": calendars,
    }

    with open(OUT_PATH, "w", encoding="utf-8") as out:
        json.dump(manifest, out, indent=2)
        out.write("\n")

    print(f"seeded {manifest['summary']}")
    print(f"contended slot: {manifest['contended']}")
    print(f"manifest written to {OUT_PATH}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"seed failed: {error}", file=sys.stderr)
        sys.exit(1)