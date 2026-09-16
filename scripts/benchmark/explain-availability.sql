-- EXPLAIN ANALYZE for the Mini Doodle read/write hot queries on seeded data.
-- Run against the postgres container:
--   docker compose exec -T postgres psql -U task1 -d task1 -f /dev/stdin \
--     < scripts/benchmark/explain-availability.sql
-- (or capture plans with (ANALYZE, BUFFERS, COSTS, VERBOSE) and paste into the
-- results report). Start + end use the seed read window so every calendar returns rows.

\set ON_ERROR_STOP on

SELECT 'availability query (findIntersectingSlots) - dense calendar' AS run;
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM calendar_slot
WHERE calendar_id = (SELECT id FROM personal_calendar ORDER BY id LIMIT 1)
  AND start_at < TIMESTAMPTZ '2035-03-15T18:00:00Z'
  AND end_at > TIMESTAMPTZ '2035-02-01T08:00:00Z'
ORDER BY start_at, end_at;

SELECT 'availability query (findIntersectingSlots) - sparse calendar' AS run;
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM calendar_slot
WHERE calendar_id = (SELECT id FROM personal_calendar ORDER BY id DESC LIMIT 1)
  AND start_at < TIMESTAMPTZ '2035-03-15T18:00:00Z'
  AND end_at > TIMESTAMPTZ '2035-02-01T08:00:00Z'
ORDER BY start_at, end_at;

SELECT 'overlap check on writes (existsOverlappingSlot)' AS run;
EXPLAIN (ANALYZE, BUFFERS)
SELECT (count(*) > 0) FROM calendar_slot
WHERE calendar_id = (SELECT id FROM personal_calendar ORDER BY id LIMIT 1)
  AND start_at < TIMESTAMPTZ '2036-01-01T18:00:00Z'
  AND end_at > TIMESTAMPTZ '2036-01-01T09:00:00Z';