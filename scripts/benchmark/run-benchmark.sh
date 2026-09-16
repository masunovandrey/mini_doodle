#!/usr/bin/env bash
#
# Runs one k6 benchmark scenario against a locally running stack and records
# environment plus sampled docker stats and postgres backend counts next to the
# k6 output. Artifacts land in scripts/benchmark/results/<scenario>-<timestamp>/.
#
# Usage:
#   scripts/benchmark/run-benchmark.sh <scenario> [extra k6 args...]
#   BASE_URL=http://host.docker.internal:8080 K6_IMAGE=grafana/k6 \
#       scripts/benchmark/run-benchmark.sh availability-reads --vus 50 --duration 2m
#
# Scenarios: availability-reads | independent-slot-writes | mixed-traffic | contention-meeting
# Requires a running stack (docker compose up --build -d) and the seed data
# (see scripts/benchmark/README.md).

set -euo pipefail

cd "$(dirname "$0")/../.."

scenario="${1:?Usage: run-benchmark.sh <scenario> [extra k6 args...]}"
shift
case "$scenario" in
    availability-reads | independent-slot-writes | mixed-traffic | contention-meeting) ;;
    *) echo "unknown scenario: $scenario" >&2; exit 2 ;;
esac

BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
K6_IMAGE="${K6_IMAGE:-grafana/k6}"
stamp="$(date +%Y%m%d-%H%M%S)"
out="scripts/benchmark/results/${scenario}-${stamp}"
mkdir -p "$out"

echo ">>> artifact dir: $out"

# --- metric sampler: docker stats + active postgres backends every 2s ---------
ids="$(docker compose ps -q 2>/dev/null | tr '\n' ' ')"
: > "$out/metrics.csv"
echo "time,dockerstats,pg_active_backends" >> "$out/metrics.csv"
(
    set +e
    while :; do
        row="$(date +%H:%M:%S)"
        stats="$(docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' $ids 2>/dev/null | tr '\n' ';')"
        backends="$(docker compose exec -T postgres psql -U task1 -d task1 -tAc "select count(*) from pg_stat_activity where datname='task1'" 2>/dev/null)"
        echo "$row,$stats,${backends:- ?}" >> "$out/metrics.csv"
        sleep 2
    done
) &
sampler_pid=$!
trap 'kill "$sampler_pid" 2>/dev/null || true' EXIT

# --- environment record --------------------------------------------------------
{
    echo "run: $scenario ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
    echo "base_url: $BASE_URL"
    echo "k6_image: $K6_IMAGE"
    echo "--- host ---"
    uname -a
    sw_vers
    echo "--- docker ---"
    docker --version
    docker compose version
    docker run --rm "$K6_IMAGE" version 2>/dev/null | head -n1 || echo "k6 version unknown"
    echo "--- containers ---"
    docker compose ps --format '{{.Service}} {{.Name}} {{.Status}}' 2>/dev/null
} > "$out/environment.md"

# --- k6 run --------------------------------------------------------------------
set +e
docker run --rm \
    --add-host host.docker.internal:host-gateway \
    -v "$PWD:/work" \
    -e BASE_URL="$BASE_URL" \
    -e BENCH_DATA=/work/scripts/benchmark/bench-data.json \
    "$K6_IMAGE" run \
        --summary-export="/work/$out/summary.json" \
        "$@" "/work/scripts/benchmark/${scenario}.js" 2>&1 | tee "$out/k6-output.txt"
k6_status=${PIPESTATUS[0]}
set -e

kill "$sampler_pid" 2>/dev/null || true
wait "$sampler_pid" 2>/dev/null || true

if [ "$k6_status" -ne 0 ]; then
    echo "k6 exited with $k6_status (see k6-output.txt)" >&2
    exit "$k6_status"
fi

echo ">>> done. artifacts:"
ls -1 "$out"
echo ">>> note: docker stats CPUPerc on macOS is host-shared, not per-container."