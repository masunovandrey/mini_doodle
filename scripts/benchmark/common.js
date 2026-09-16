// Shared helpers for the Mini Doodle benchmark scenarios.
// When run through run-benchmark.sh the base URL is host.docker.internal so a
// k6 container can reach the app published on localhost:8080. Override with
// --env BASE_URL=http://localhost:8080 for a locally installed k6 CLI.

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

export function jsonHeaders(extra) {
    return Object.assign({ 'Content-Type': 'application/json' }, extra || {});
}

// Shared manifest produced by seed.py (calendars, contended slot, read window).
// BENCH_DATA is set by run-benchmark.sh; falls back to the local file for CLI runs.
export function benchData() {
    return JSON.parse(open(__ENV.BENCH_DATA || 'bench-data.json'));
}

const data = benchData();

export function randomCalendar(data) {
    return data.calendars[Math.floor(Math.random() * data.calendars.length)];
}

// Base for independent write scenarios: 2037-03-20T00:00:00Z. Past the seeded
// 2035 window and clear of any slots persisted by earlier benchmark runs.
const WRITE_BASE_MS = Date.UTC(2037, 2, 20, 0, 0, 0);

// Slots are one hour long and advanced by a fixed stride; stride must exceed
// the duration for consecutive writes not to overlap.
const SLOT_DURATION_MS = 3600000; // 1h
const SLOT_STRIDE_MS = 2 * 3600000; // 2h

// Collision-free write coordinates for a single benchmark run.
//
//   calendar = (__VU - 1 + __ITER * vus) % n   -> at a given __ITER every VU
//                                                 maps to a distinct calendar
//                                                 (requires vus <= n)
//   start    = base + __ITER * STRIDE           -> strictly increasing within a
//                                                 VU, so a VU never re-covers
//                                                 an interval it already wrote
//
// Together these make each (VU, iteration) write to a unique (calendar, time),
// so writes always succeed with 201 within a run. Pass a different `base` for
// scenarios that must share a database (e.g. mixed-traffic) so their intervals
// never collide with another scenario's writes. Times are deterministic:
// rerunning a scenario against the same (already written) database yields 409s
// by design - wipe and reseed for a repeatable baseline.
export function nextWriteSlot(vus, base = WRITE_BASE_MS) {
    const n = data.calendars.length;
    if (vus > n) {
        throw new Error(`vus (${vus}) must not exceed seeded calendars (${n})`);
    }
    const calendar = data.calendars[(__VU - 1 + __ITER * vus) % n];
    const startMs = base + __ITER * SLOT_STRIDE_MS;
    return {
        calendar,
        start: new Date(startMs).toISOString(),
        end: new Date(startMs + SLOT_DURATION_MS).toISOString(),
    };
}