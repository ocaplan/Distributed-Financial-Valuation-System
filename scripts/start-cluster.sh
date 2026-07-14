#!/usr/bin/env bash
# Start a 3-node DFVS cluster on localhost.
#
# Each node runs as its own JVM process so leader election, heartbeats, task
# pulls, and result submissions all flow over real HTTP between processes.
# Logs are written to logs/node-N.log; PIDs to .cluster/node-N.pid.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
JAR="$ROOT/target/distributed-financial-valuation-system-1.0.0.jar"

if [[ ! -f "$JAR" ]]; then
    echo "Building project..."
    mvn -q package -DskipTests
fi

mkdir -p logs .cluster data

CLUSTER="node-1=localhost:8081,node-2=localhost:8082,node-3=localhost:8083"

start_node() {
    local id="$1"
    local port="$2"
    local pidfile=".cluster/${id}.pid"
    local logfile="logs/${id}.log"

    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "$id already running (pid $(cat "$pidfile")); skipping"
        return
    fi

    echo "Starting $id on port $port..."
    nohup java -jar "$JAR" \
        --server.port="$port" \
        --dfvs.node-id="$id" \
        --dfvs.cluster.nodes="$CLUSTER" \
        > "$logfile" 2>&1 &
    echo $! > "$pidfile"
    echo "  pid=$(cat "$pidfile")  log=$logfile"
}

start_node node-1 8081
start_node node-2 8082
start_node node-3 8083

echo
echo "Cluster started. Waiting for leader election to settle..."
sleep 6

for port in 8081 8082 8083; do
    echo "--- localhost:$port /internal/status ---"
    curl -s "http://localhost:$port/internal/status" || echo "(node not responding)"
    echo
done

echo
echo "Use scripts/status.sh to inspect the cluster, scripts/kill-leader.sh to"
echo "demo failover, or scripts/stop-cluster.sh to shut everything down."
