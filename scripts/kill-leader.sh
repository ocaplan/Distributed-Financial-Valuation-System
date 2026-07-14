#!/usr/bin/env bash
# Find whichever node currently believes it is the leader and SIGKILL it,
# so the grader can observe Bully re-election among the surviving nodes.

set -uo pipefail

cd "$(dirname "$0")/.."

if [[ ! -d .cluster ]]; then
    echo "No .cluster directory — is the cluster running? Run scripts/start-cluster.sh first."
    exit 1
fi

leader_node=""
for port in 8081 8082 8083; do
    resp="$(curl -s "http://localhost:$port/internal/status" || true)"
    # Crude JSON inspection — avoids needing jq.
    if echo "$resp" | grep -q '"isLeader":true'; then
        leader_node="$(echo "$resp" | sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p' | head -1)"
        leader_port="$port"
        break
    fi
done

if [[ -z "$leader_node" ]]; then
    echo "No leader currently elected. Try again in a few seconds."
    exit 1
fi

pidfile=".cluster/${leader_node}.pid"
if [[ ! -f "$pidfile" ]]; then
    echo "Found leader $leader_node on port $leader_port but no pid file at $pidfile."
    exit 1
fi
pid="$(cat "$pidfile")"
echo "Killing leader $leader_node (pid $pid, port $leader_port) with SIGKILL..."
kill -9 "$pid" || true
rm -f "$pidfile"

echo "Wait ~6s, then run scripts/status.sh to see the new leader."
