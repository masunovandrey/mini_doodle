# Mini Doodle service

A Java 21 / Spring Boot service for managing personal calendar slots, converting an available slot into a meeting, and querying calendar availability. The service exposes a JSON-over-HTTP REST API and persists all state in PostgreSQL.

## Prerequisites

- Docker Desktop (or Docker Engine) with the Docker Compose plugin.
- Port `8080` available on the host.

No local Java, Maven, or PostgreSQL installation is required for the Docker workflow.

## Start, stop, and persistence

From this directory, build and start the application and PostgreSQL:

```sh
docker compose up --build
```

The application listens on `http://localhost:8080`. Compose waits for the PostgreSQL health check before starting the application. The application receives its connection configuration through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

Stop the stack while retaining database data:

```sh
docker compose down
```

Start it again with `docker compose up --build`; the named `postgres-data` volume preserves users, slots, and meetings. To remove that persisted data deliberately, run:

```sh
docker compose down -v
```

Use `docker compose logs -f app` to inspect application startup or API failures. A failed connection commonly means port `8080` is already in use or Docker is not running.

## API conventions

All request and response bodies are JSON. IDs are server-generated UUIDs. Timestamps accept ISO-8601 offset datetimes and successful responses normalize them to UTC (`Z`). Slots are half-open intervals: `[start, end)`.

Errors are RFC 9457 Problem Details JSON. Invalid input is `400`, a missing user/calendar/slot is `404`, and an overlapping slot, invalid conversion, or mutation of a meeting-backed slot is `409`.

Examples below use `curl`; replace the generated IDs with the returned values.

## API contract, Swagger UI, and collection

The running service publishes its OpenAPI description:

```sh
curl -s http://localhost:8080/v3/api-docs
```

The generated document (`/v3/api-docs`) describes every endpoint together with its request and response schemas, the `uuid` path parameters, the ISO-8601 UTC date-times, the documented `400`/`404`/`409` RFC 9457 Problem Details responses, and the `ETag`/`If-Match` optimistic-concurrency contract.

Browse and execute the endpoints interactively with Swagger UI at:

```text
http://localhost:8080/swagger-ui/index.html
```

## Create a user and personal calendar (`POST /users`)

```sh
curl -i -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"alex@example.com","name":"Alex"}'
```

Successful result: `201 Created` with a body such as:

```json
{"id":"USER_ID","email":"alex@example.com","name":"Alex","calendarId":"CALENDAR_ID"}
```

Retrieve the user and its personal calendar with `GET /users/{userId}`:

```sh
curl -i http://localhost:8080/users/USER_ID
```

## Create and manage slots

Create an available slot with `POST /calendars/{calendarId}/slots`:

```sh
curl -i -X POST http://localhost:8080/calendars/CALENDAR_ID/slots \
  -H 'Content-Type: application/json' \
  -d '{"start":"2026-12-15T09:00:00Z","end":"2026-12-15T10:00:00Z"}'
```

Successful result: `201 Created` and `{"id":"SLOT_ID","start":"2026-12-15T09:00:00Z","end":"2026-12-15T10:00:00Z","state":"FREE"}`.

Read, replace its time range, mark it busy/free, or delete it:

```sh
curl -i http://localhost:8080/calendars/CALENDAR_ID/slots/SLOT_ID
curl -i -X PUT http://localhost:8080/calendars/CALENDAR_ID/slots/SLOT_ID -H 'Content-Type: application/json' -d '{"start":"2026-12-15T10:00:00Z","end":"2026-12-15T11:00:00Z"}'
curl -i -X PATCH http://localhost:8080/calendars/CALENDAR_ID/slots/SLOT_ID -H 'Content-Type: application/json' -d '{"state":"BUSY"}'
curl -i -X DELETE http://localhost:8080/calendars/CALENDAR_ID/slots/SLOT_ID
```

The `GET /calendars/{calendarId}/slots/{slotId}`, `PUT`, and `PATCH` operations return `200 OK`; deletion returns `204 No Content`. A reversed/equal range or invalid state returns `400`. An unknown calendar or slot returns `404`. An overlapping range returns `409`.

## Convert a free slot into a meeting

Create a fresh `FREE` slot, then call `POST /calendars/{calendarId}/slots/{slotId}/meeting`:

```sh
curl -i -X POST http://localhost:8080/calendars/CALENDAR_ID/slots/SLOT_ID/meeting \
  -H 'Content-Type: application/json' \
  -d '{"title":"Planning","description":"Quarterly planning","participants":["alex@example.com","sam@example.com"]}'
```

Successful result: `201 Created` with a meeting body containing its `id`, the source `slotId`, title, optional description, participants, and `slotState: "BUSY"`. The title and participant values must be non-blank; invalid input is `400`. A busy/already-converted slot produces `409`, and a missing calendar or slot produces `404`. A meeting-backed slot cannot later be changed or deleted and therefore returns `409` for those operations.

## Query availability

Query stored free/busy intervals using `GET /calendars/{calendarId}/availability` and an explicit time frame:

```sh
curl -i 'http://localhost:8080/calendars/CALENDAR_ID/availability?start=2026-12-15T08:00:00Z&end=2026-12-15T18:00:00Z'
```

Successful result: `200 OK`, for example:

```json
{
  "calendarId":"CALENDAR_ID",
  "start":"2026-12-15T08:00:00Z",
  "end":"2026-12-15T18:00:00Z",
  "intervals":[
    {"start":"2026-12-15T09:00:00Z","end":"2026-12-15T10:00:00Z","state":"FREE"},
    {"start":"2026-12-15T10:00:00Z","end":"2026-12-15T11:00:00Z","state":"BUSY"}
  ]
}
```

Only stored slots that intersect the frame are returned. Results are clipped to the requested frame and adjacent same-state intervals are merged. The response can contain an empty `intervals` array. A missing, malformed, or non-positive frame is `400`; an unknown calendar is `404`.

## Operational visibility (Actuator health)

The app exposes only Spring Boot Actuator's `health` endpoint; no other Actuator endpoints are
exposed. Health, readiness, and liveness are available at:

```text
GET /actuator/health            -> {"status":"UP"}
GET /actuator/health/readiness  -> {"status":"UP"}   (group used by the Compose healthcheck)
GET /actuator/health/liveness   -> {"status":"UP"}
```

```sh
curl -fsS http://localhost:8080/actuator/health
```

The Compose deployment waits for PostgreSQL (`pg_isready`), then for the app's readiness group
before considering the app healthy.

## Performance and concurrency benchmark

A reproducible benchmark suite lives in `scripts/benchmark/` (see its `README.md`):

| Artifact | Purpose |
|---|---|
| `seed.py` | Seeds 200 calendars / 10,000 non-overlapping slots via the HTTP API and writes `bench-data.json` |
| k6 scenarios | `availability-reads`, `independent-slot-writes`, `mixed-traffic`, `contention-meeting` |
| `run-benchmark.sh` | Runs a scenario in the `grafana/k6` container and captures metrics + environment |
| `explain-availability.sql` | `EXPLAIN (ANALYZE, BUFFERS)` for the availability and overlap queries |
| `benchmark-report.md` | Full baseline report (environment, results, resources, query plans) |
| `results/` | Raw per-run artifacts: `k6-output.txt`, `summary.json`, `metrics.csv`, `environment.md` |

Baseline summary (2026-09-16, local Docker Compose; full details in `benchmark-report.md`):

| Scenario | req/s | p50 | p95 | Error rate |
|---|---|---|---|---|
| availability-reads | ~1,065 | 9.2 ms | 25.8 ms | 0.0104% |
| independent-slot-writes | ~1,023 | 11.4 ms | 26.9 ms | 0.0107% (99.99% → 201) |
| mixed-traffic (80/20) | ~1,761 | 44.3 ms | 141.7 ms | 0.0054% |
| contention-meeting | — | 105 ms | — | exactly 1 × 201, 24 × 409 |

Findings: `EXPLAIN ANALYZE` showed sub-millisecond plans served by the existing GiST
exclusion index, no sequential scans, and a trivial sort — no index or cache change was
warranted (see `docs/adr/adr-0006-no-cache-until-measured.md`). To rerun a clean baseline:
wipe and reseed first (`docker compose down -v`), since write scenarios are deterministic.

## Verification

After startup, run the user, slot, meeting, and availability commands above in order. To confirm persistence, create a user, run `docker compose down`, run `docker compose up --build`, and repeat `GET /users/{userId}`. It should still return `200 OK`.

For a scripted black-box check of a running deployment, execute:

```sh
scripts/smoke-test.sh
```

It walks user -> slot -> meeting -> availability and asserts the documented `409` for a meeting-backed slot. Set `BASE_URL` to target a different host. The script needs `curl` and `python3`.

For the Maven integration test suite, Docker must be running because the tests use Testcontainers:

```sh
./mvnw test
```
