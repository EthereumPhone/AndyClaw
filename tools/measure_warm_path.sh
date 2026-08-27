#!/usr/bin/env bash
# Measure the Phase 2 exit criterion: median warm-path latency for a repeated action,
# and how many model calls it still costs.
#
#   agent-first-plan.md, Phase 2: "median warm-path latency for a repeated messaging
#   action < 1.5 s with zero model calls."
#
# The app logs one line per agent run under the tag AgentRunMetrics:
#
#   durationMs=812 modelCalls=1 iterations=2 flowInvocations=1 flowCompleted=1 \
#   flowAborted=0 flowSteps=8 flowMedianMs=684
#
#   durationMs   AgentLoop.run entry -> onComplete, the whole turn
#   modelCalls   every client.streamMessage in the run, sub-agents included
#   flow*        FlowMetrics: replays and the median of the completed ones
#
# Read both numbers. A fast turn that still asks a model twenty times has not replaced
# the display loop, it has just cached part of it.
#
# Usage:
#   tools/measure_warm_path.sh                 # watch 20 runs you trigger yourself
#   tools/measure_warm_path.sh -n 10           # watch 10
#   tools/measure_warm_path.sh --heartbeat -n 20   # trigger 20 heartbeats over adb
#   tools/measure_warm_path.sh --interval 25   # seconds between triggered heartbeats
#
# --heartbeat uses the debug broadcast that already exists
# (com.android.server.andyclaw.HEARTBEAT_NOW). There is deliberately no adb path that
# injects an arbitrary prompt: that would be an unauthenticated way into the agent, which
# is the hole Phase 1 exists to close. For a messaging action, drive it from the launcher
# and let this script watch.

set -euo pipefail

RUNS=20
INTERVAL=20
TRIGGER=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--runs) RUNS="$2"; shift 2 ;;
        --interval) INTERVAL="$2"; shift 2 ;;
        --heartbeat) TRIGGER="heartbeat"; shift ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if ! adb get-state >/dev/null 2>&1; then
    echo "No device. Attach the dgen1 and re-run." >&2
    exit 1
fi

echo "Watching for $RUNS agent runs. Clearing logcat."
adb logcat -c

LOG="$(mktemp -t warmpath.XXXXXX)"
trap 'rm -f "$LOG"' EXIT

adb logcat -s AgentRunMetrics:I > "$LOG" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true; rm -f "$LOG"' EXIT

if [[ "$TRIGGER" == "heartbeat" ]]; then
    echo "Triggering $RUNS heartbeats, ${INTERVAL}s apart."
    for ((i = 1; i <= RUNS; i++)); do
        adb shell am broadcast -a com.android.server.andyclaw.HEARTBEAT_NOW >/dev/null
        printf '  triggered %d/%d\n' "$i" "$RUNS"
        [[ $i -lt $RUNS ]] && sleep "$INTERVAL"
    done
else
    echo "Perform the same action $RUNS times. Counting as they complete."
fi

while :; do
    seen=$(grep -c 'durationMs=' "$LOG" || true)
    printf '\r  %s/%s runs seen' "$seen" "$RUNS"
    [[ "$seen" -ge "$RUNS" ]] && break
    sleep 2
done
printf '\n\n'

LINES="$(grep -o 'durationMs=.*' "$LOG" | head -n "$RUNS")"
echo "$LINES"
echo

median() {  # median of the numbers on stdin
    sort -n | awk '{ a[NR] = $1 } END { if (NR == 0) { print "-" } else { print a[int((NR + 1) / 2)] } }'
}

MED_MS="$(echo "$LINES" | sed -n 's/.*durationMs=\([0-9]*\).*/\1/p' | median)"
MED_CALLS="$(echo "$LINES" | sed -n 's/.*modelCalls=\([0-9]*\).*/\1/p' | median)"
MEAN_CALLS="$(echo "$LINES" | sed -n 's/.*modelCalls=\([0-9]*\).*/\1/p' \
    | awk '{ s += $1 } END { if (NR) printf "%.2f", s / NR; else print "-" }')"
LAST="$(echo "$LINES" | tail -1)"

echo "─── $(echo "$LINES" | grep -c . ) runs ───"
echo "median durationMs : ${MED_MS}   $( [ "${MED_MS}" != "-" ] && [ "${MED_MS}" -lt 1500 ] && echo 'PASS (< 1500)' || echo 'FAIL (>= 1500)' )"
echo "median modelCalls : ${MED_CALLS}"
echo "mean modelCalls   : ${MEAN_CALLS}"
echo "flow counters     : ${LAST#*flowInvocations=}"
echo
echo "Both halves matter: the criterion is < 1.5 s AND no model in the replay."
