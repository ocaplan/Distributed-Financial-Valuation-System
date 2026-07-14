#!/usr/bin/env bash
# Stop every node started by start-cluster.sh.

set -uo pipefail

cd "$(dirname "$0")/.."

if [[ ! -d .cluster ]]; then
    echo "No .cluster directory; nothing to stop."
    exit 0
fi

for pidfile in .cluster/*.pid; do
    [[ -f "$pidfile" ]] || continue
    name="$(basename "$pidfile" .pid)"
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
        echo "Stopping $name (pid $pid)..."
        kill "$pid"
        # Wait briefly for graceful shutdown.
        for _ in 1 2 3 4 5; do
            sleep 1
            kill -0 "$pid" 2>/dev/null || break
        done
        if kill -0 "$pid" 2>/dev/null; then
            echo "  ...still alive, sending SIGKILL"
            kill -9 "$pid" || true
        fi
    else
        echo "$name (pid $pid) already stopped"
    fi
    rm -f "$pidfile"
done

# Clean shared database so the next run starts fresh.
if [[ -d data ]]; then
    rm -rf data
    echo "Cleared data/ (shared H2 file DB)"
fi

echo "Cluster stopped."
