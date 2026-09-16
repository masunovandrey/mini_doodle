# Mini Doodle performance & concurrency baseline (NEXT-STEPS #5)

Reusable scripts for booking a scale-aware baseline before any optimisation:

| Artifact | Purpose |
|---|---|
| `seed.py` | Seeds `NUM_CALENDARS` users/calendars and `SLOTS_PER_CALENDAR` non-overlapping slots via the HTTP API, marks every `BUSY_EVERY`-th slot BUSY, and writes `bench-data.json` (calendars, contended slot, read window, summary). |
| `common.js` | Shared k6 helpers (base URL, JSON headers, manifest loading). |
| `availability-reads.js` | Read-only scenario over random calendars within the seed read window. |
| `independent-slot-writes.js` | Collision-free writers: each (VU, iteration) targets a unique `(calendar, time)` in 2037, so every write succeeds with 201. |
| `mixed-traffic.js` | 80 read / 20 write VUs in parallel. |
| `contention-meeting.js` | 25 VUs race one meeting conversion; exactly one `201`, rest `409`. |
| `run-benchmark.sh` | Runs a scenario via the `grafana/k6` container, samples `docker stats` + `pg_stat_activity`, writes `environment.md`, k6 text output, and `summary.json`. |
| `explain-availability.sql` | `EXPLAIN (ANALYZE, BUFFERS)` for the availability and overlap queries. |

## Prerequisites

- The stack is running and the app answers on `localhost:8080` (`docker compose up --build -d`).
- `grafana/k6` image available (`docker pull grafana/k6`), and `python3` on the host.
- k6 reaches the app via `host.docker.internal`; if you run k6 as a local binary instead,
  pass `--env BASE_URL=http://localhost:8080 --env BENCH_DATA=scripts/benchmark/bench-data.json`.

## 1. Seed representative data

```sh
docker compose down -v && docker compose up --build -d   # clean, deterministic baseline
python3 scripts/benchmark/seed.py                         # 200 calendars, 10k slots
```

Defaults: `200` calendars x `50` slots = `10000` slots, ~20% BUSY. Override via env:
`SEED_TAG`, `NUM_CALENDARS`, `SLOTS_PER_CALENDAR`, `BUSY_EVERY`, `BASE_URL`.
The same `SEED_TAG` reproduces the identical dataset; re-seed only on a clean DB.

Note: seeding uses only 2 slot profiles (dense/sparse) to stay representative yet
reproducible; large randomisation would make baselines incomparable.

## 2. Record a baseline

```sh
scripts/benchmark/run-benchmark.sh availability-reads
scripts/benchmark/run-benchmark.sh independent-slot-writes
scripts/benchmark/run-benchmark.sh mixed-traffic
scripts/benchmark/run-benchmark.sh contention-meeting
```

Scenario VU counts and durations are fixed constants in each JS file (deterministic,
comparable baselines); see `WRITE_VUS` etc. to change them intentionally.

Because write coordinates are deterministic, re-running a write scenario (or the mixed
scenario) against the same already-written database produces 409 on every write by design.
To re-record a baseline from scratch: wipe, reseed, then run the scenarios:

```sh
docker compose down -v && docker compose up --build -d
python3 scripts/benchmark/seed.py
scripts/benchmark/run-benchmark.sh independent-slot-writes
```

Each run:

- samples CPU/memory (`docker stats`) and active postgres backends every 2s;
- saves `k6-output.txt` (throughput, p50/p95 from the `http_req_duration` trend,
  `http_req_failed` rate, per-status counts), `summary.json`, `metrics.csv`,
  `environment.md`.

**Contention acceptance:** `contention-meeting` gates on `valid_outcome_201_or_409 rate==1`
(every request must be a valid contender outcome). Exact-total gate is not possible via k6
counter thresholds (evaluated per time bucket), so the acceptance evidence is recorded from
the run: `meeting_winners_201` must equal 1 (single 201 Created) and `meeting_losers_409` must
equal 24 (every other contender rejected with 409) in the end-of-test summary and report.

## 3. Query plan

```sh
docker compose exec -T postgres psql -U task1 -d task1 -f /dev/stdin \
  < scripts/benchmark/explain-availability.sql
```

Capture the plans into the results report. Current schema (V2) indexes the write path
with a GiST exclusion `(calendar_id, tstzrange(start_at,end_at,'[)'))` and a btree
`(calendar_id, start_at)`. If the availability plan shows a seq scan or a sort, a
composite `(calendar_id, start_at, end_at)` btree may be justified -> add it as a new
Flyway migration (V5) and re-run the plan + scenarios to document the before/after.

## 4. Report

For each named environment (host, OS, docker/k6 versions, stack, seed tag, run params),
commit `scripts/benchmark/results/<scenario>-<timestamp>/` alongside the recorded baseline;
the 2026-09-16 baseline is summarised in `scripts/benchmark/benchmark-report.md`, which covers:

- throughput (req/s), p50/p95 latency, HTTP error rate per scenario;
- DB connection use (pool = 10 default, observed `pg_stat_activity` peak);
- CPU/memory from the metrics CSV (note: mac `docker stats` CPU% is host-shared);
- EXPLAIN ANALYZE findings and any index/query change applied.

## Guardrail

No Redis or other cache will be added for this benchmark. Only a data-structure
change (index, query) is eligible, and only if EXPLAIN ANALYZE demonstrates the
bottleneck and the change is re-benchmarked before and after.