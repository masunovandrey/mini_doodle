// Independent slot writes: each VU writes to a collision-free (calendar, time)
// combo in 2037 (past the seeded data), so every write succeeds with 201.
//
// Run: scripts/benchmark/run-benchmark.sh independent-slot-writes [extra k6 args]

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, nextWriteSlot, jsonHeaders } from './common.js';

const WRITE_VUS = 20;

export const options = {
    scenarios: {
        independent_slot_writes: {
            executor: 'constant-vus',
            vus: WRITE_VUS,
            duration: '60s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const { calendar, start, end } = nextWriteSlot(WRITE_VUS);
    const res = http.post(
        `${BASE_URL}/calendars/${calendar.id}/slots`,
        JSON.stringify({ start, end }),
        { headers: jsonHeaders() }
    );
    check(res, {
        'slot created with 201': (r) => r.status === 201,
    });
}