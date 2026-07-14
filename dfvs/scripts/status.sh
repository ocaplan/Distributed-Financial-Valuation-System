#!/usr/bin/env bash
# Print each node's view of the cluster.

set -uo pipefail

for port in 8081 8082 8083; do
    echo "=== localhost:$port ==="
    curl -s --max-time 2 "http://localhost:$port/internal/status" \
        || echo "(no response — node may be down)"
    echo
done
