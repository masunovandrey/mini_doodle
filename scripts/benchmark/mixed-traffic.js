// Mixed read/write traffic: availability reads (80 VUs) balanced against
// independent slot writes (20 VUs), reads in the seeded window, writes in 2037
// so they stay clear of the seed and of each other.
//
// Run: scripts/benchmark/run-benchmark.sh mixed-traffic [extra k6 args]

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, benchData, randomCalendar, nextWriteSlot, jsonHeaders } from './common.js';

const data = benchData();
const WINDOW = data.readWindow;
const WRITE_VUS = 20;

export const options = {
    scenarios: {
        reads: {
            executor: 'constant-vus',
            vus: 80,
            duration: '60s',
            exec: 'availabilityRead',
        },
        writes: {
            executor: 'constant-vus',
            vus: WRITE_VUS,
            duration: '60s',
            exec: 'independentWrite',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export function availabilityRead() {
    const calendar = randomCalendar(data);
    const res = http.get(
        `${BASE_URL}/calendars/${calendar.id}/availability?start=${WINDOW.start}&end=${WINDOW.end}`
    );
    check(res, {
        'availability returns 200': (r) => r.status === 200,
    });
}

export function independentWrite() {
    // Base 2037-04-01 keeps mixed writes clear of independent-slot-writes (2037-03-20+).
    const base = Date.UTC(2037, 3, 1, 0, 0, 0);
    const { calendar, start, end } = nextWriteSlot(WRITE_VUS, base);
    const res = http.post(
        `${BASE_URL}/calendars/${calendar.id}/slots`,
        JSON.stringify({ start, end }),
        { headers: jsonHeaders() }
    );
    check(res, {
        'slot created with 201': (r) => r.status === 201,
    });
}