// Contention: every VU races to convert the SAME seeded slot with the SAME
// If-Match ETag. Exactly one request must win with 201 Created; every other
// valid contender must receive 409 Conflict.
//
// k6 Counter metrics aggregate across all VUs, but thresholds on counters are
// evaluated per time bucket (so an exact-count gate like count==1 cannot pass).
// Instead we gate on a Rate: every request must be a valid contender outcome
// (201 or 409). The exact totals (meeting_winners_201 == 1 and
// meeting_losers_409 == iterations - 1) are printed in the end-of-test summary
// and recorded in the results report as the acceptance evidence.
//
// Run: scripts/benchmark/run-benchmark.sh contention-meeting [extra k6 args]

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, benchData, jsonHeaders } from './common.js';

const data = benchData();
const target = data.contended;

if (!target) {
    throw new Error('bench-data.json has no contested slot; run seed.py first');
}

const iterations = 25;

const winners = new Counter('meeting_winners_201');
const losers = new Counter('meeting_losers_409');
const validOutcome = new Rate('valid_outcome_201_or_409');

export const options = {
    scenarios: {
        meeting_race: {
            executor: 'shared-iterations',
            vus: iterations,
            iterations: iterations,
            maxDuration: '30s',
        },
    },
    thresholds: {
        valid_outcome_201_or_409: ['rate==1'],
    },
};

export default function () {
    const res = http.post(
        `${BASE_URL}/calendars/${target.calendarId}/slots/${target.slotId}/meeting`,
        JSON.stringify({
            title: 'benchmark race',
            description: 'contended meeting conversion',
            participants: ['bench@example.com'],
        }),
        { headers: jsonHeaders({ 'If-Match': target.etag }) }
    );

    const valid = res.status === 201 || res.status === 409;
    if (res.status === 201) {
        winners.add(1);
    } else if (res.status === 409) {
        losers.add(1);
    }
    validOutcome.add(valid);
    check(res, {
        'responds with 201 or 409 only': valid,
    });
}