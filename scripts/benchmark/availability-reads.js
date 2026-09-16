// Availability read load: every VU queries availability for a random calendar.
//
// Run: scripts/benchmark/run-benchmark.sh availability-reads [extra k6 args]
// e.g.: scripts/benchmark/run-benchmark.sh availability-reads --vus 50 --duration 2m

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, benchData, randomCalendar } from './common.js';

const data = benchData();
const WINDOW = data.readWindow;

export const options = {
    scenarios: {
        availability_reads: {
            executor: 'constant-vus',
            vus: 20,
            duration: '60s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const calendar = randomCalendar(data);
    const res = http.get(
        `${BASE_URL}/calendars/${calendar.id}/availability?start=${WINDOW.start}&end=${WINDOW.end}`
    );
    check(res, {
        'availability returns 200': (r) => r.status === 200,
        'availability returns intervals': (r) => r.json('intervals') !== undefined,
    });
}