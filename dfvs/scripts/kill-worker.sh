#!/usr/bin/env bash
# Kill a specific node by id (e.g. node-2). Used to demo worker failure +
# task reassignment without touching the current leader.

set -uo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <node-id>   e.g. $0 node-2"
    exit 1
fi

node="$1"
cd "$(dirname "$0")/.."

pidfile=".cluster/${node}.pid"
if [[ ! -f "$pidfile" ]]; then
    echo "No pid file for $node at $pidfile."
    exit 1
fi
pid="$(cat "$pidfile")"

if ! kill -0 "$pid" 2>/dev/null; then
    echo "$node (pid $pid) is not running."
    rm -f "$pidfile"
    exit 0
fi

echo "Killing $node (pid $pid) with SIGKILL..."
kill -9 "$pid" || true
rm -f "$pidfile"
echo "$node stopped. The leader should detect via heartbeat within ~6s and reassign its tasks."
