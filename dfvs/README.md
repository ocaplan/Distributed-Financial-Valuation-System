# Distributed Financial Valuation System (DFVS)

A peer-to-peer distributed system that performs DCF (Discounted Cash Flow)
valuations for portfolios of companies across a cluster of independent
JVM processes. Implements the core DS concepts in the project spec **from
scratch** — no external coordination service:

- **Bully-algorithm leader election** over HTTP (no ZooKeeper/Curator).
- **Pull-based task queue** distributed over HTTP from leader to workers.
- **Heartbeat-based failure detection** (2-strike) over HTTP, in **both**
  directions: the leader heartbeats workers, and each worker heartbeats the
  leader so leader crashes trigger re-election.
- **Automatic task reassignment** when a worker dies in the middle of a task.
- **Idempotent result storage** — first result for `(jobId, ticker)` wins.
- **Leader recovery** from a persistent task log shared by all nodes via H2 in
  `AUTO_SERVER=TRUE` mode, so a freshly-elected leader sees the previous
  leader's in-flight tasks. Analyst-supplied overrides (WACC, FCF, etc.) are
  persisted on each task row so failover preserves them losslessly.
- **SSE result streaming** for clients.

Each cluster node runs **the same JAR**, started with different
`--server.port` / `--dfvs.node-id` flags. Every node is both an election
participant and a worker; the elected leader additionally coordinates work
distribution.

## Architecture

```
                                           (Bully election + heartbeats + task pull
                                            + result submit — all over HTTP)
┌──────────────┐       HTTP/JSON        ┌──────────────────────────────────────────────┐
│   Financial  │ ◄────────────────────► │ Cluster of independent JVM processes:        │
│   Analyst    │                        │                                              │
└──────────────┘                        │   ┌──────────┐   ┌──────────┐   ┌──────────┐ │
                                        │   │  node-1  │   │  node-2  │   │  node-3  │ │
                                        │   │  :8081   │ ◄►│  :8082   │ ◄►│  :8083   │ │
                                        │   │  worker  │   │  worker  │   │ LEADER + │ │
                                        │   │          │   │          │   │  worker  │ │
                                        │   └──────────┘   └──────────┘   └──────────┘ │
                                        │         │              │              │      │
                                        │         └──────────────┴──────────────┘      │
                                        │                        ▼                     │
                                        │              ┌──────────────────┐            │
                                        │              │ H2 (AUTO_SERVER) │            │
                                        │              │ shared task log  │            │
                                        │              └──────────────────┘            │
                                        └──────────────────────────────────────────────┘
```

## Prerequisites

- Java 17+ (tested on 17 and 23)
- Maven 3.8+

No external services. The shared data store is H2 file-mode with `AUTO_SERVER=TRUE`,
so the first node to start hosts an in-process TCP server and the rest connect
as TCP clients automatically.

## Build, run, kill, repeat

```bash
mvn package -DskipTests

# Start a 3-node cluster on ports 8081/8082/8083.
scripts/start-cluster.sh

# Inspect everyone's view of the cluster.
scripts/status.sh

# Submit a job (any node will route to the leader; the example targets node-3).
curl -X POST http://localhost:8083/valuations \
  -H "Content-Type: application/json" \
  -d '{"companies":[{"ticker":"AAPL"},{"ticker":"MSFT"},{"ticker":"GOOGL"}]}'

# Kill the elected leader. Wait ~6 s and re-run scripts/status.sh — a new node
# will have taken over and the kept-alive workers will be re-targeted.
scripts/kill-leader.sh

# Submit a job to the new leader and watch it complete.
curl -X POST http://localhost:8082/valuations -d '...'

# Shut everything down + wipe the shared DB.
scripts/stop-cluster.sh
```

Logs land under `logs/node-N.log`; PIDs under `.cluster/node-N.pid`.

### Single-node mode

Want a simpler one-process run for poking at the API?

```bash
java -jar target/distributed-financial-valuation-system-1.0.0.jar
# Self-elects as node-1 on port 8080 with no peers.
```

## Demonstrating failover end-to-end

This is the script the grader can copy-paste to see every distributed-systems
property running. Each command produces visible output you can screenshot.

```bash
# 0. From the project root, package and start the cluster.
mvn package -DskipTests
scripts/start-cluster.sh

# 1. Confirm exactly one leader is elected and all three nodes agree.
scripts/status.sh
# Expected: node-3 has "isLeader":true; node-1 and node-2 both say "leaderId":"node-3".

# 2. Submit a portfolio job to the leader. Distribution is across THREE JVMs.
JOB=$(curl -s -X POST http://localhost:8083/valuations \
  -H "Content-Type: application/json" \
  -d '{"companies":[{"ticker":"AAPL"},{"ticker":"MSFT"},{"ticker":"GOOGL"},
                    {"ticker":"AMZN"},{"ticker":"TSLA"},{"ticker":"META"}]}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['jobId'])")
echo "Submitted job $JOB"

# 3. Poll for completion. Each per-company log line shows which node ran it.
sleep 4
curl -s "http://localhost:8083/valuations/$JOB" | python3 -m json.tool

# 4. Confirm work was distributed across separate JVM processes.
grep "completed $JOB" logs/node-*.log

# 5. Demonstrate leader failover. SIGKILLs the elected leader.
scripts/kill-leader.sh

# 6. Wait ~6 s, then check again. A surviving node has taken over with a
#    higher epoch number.
sleep 8
scripts/status.sh

# 7. Submit a new job to the NEW leader (whichever port still answers).
NEWLEADER=$(for p in 8081 8082 8083; do
    curl -sf "http://localhost:$p/internal/status" \
        | grep -q '"isLeader":true' && echo $p && break
done)
echo "New leader is on port $NEWLEADER"
curl -s -X POST "http://localhost:$NEWLEADER/valuations" \
  -H "Content-Type: application/json" \
  -d '{"companies":[{"ticker":"NVDA"},{"ticker":"JPM"}]}'

# 8. Demonstrate worker failover. With the leader still up, kill a worker
#    while it has tasks in flight — heartbeats will detect within ~6 s and
#    its tasks will be reassigned.
scripts/kill-worker.sh node-1
grep -E "(DEAD|reassigned)" logs/node-*.log | tail

# 9. Shut everything down and wipe the shared DB.
scripts/stop-cluster.sh
```

The same scenarios run automatically and assertively in `ClusterFailoverIT`
(`mvn test -Ddfvs.integration=true -Dtest=ClusterFailoverIT`).

## API

### Submit a valuation job

```bash
curl -X POST http://localhost:8083/valuations \
  -H "Content-Type: application/json" \
  -d '{
    "companies": [
      {"ticker": "AAPL"},
      {"ticker": "MSFT"},
      {"ticker": "GOOGL"}
    ]
  }'
```

Response `202 Accepted`:

```json
{ "jobId": "a1b2c3d4", "status": "QUEUED", "totalCompanies": 3, "tickers": ["AAPL","MSFT","GOOGL"] }
```

A non-leader returns `503 Service Unavailable` with `leaderId` and `leaderUrl`
in the body so the client knows where to retry.

### Per-company overrides

```bash
curl -X POST http://localhost:8083/valuations \
  -H "Content-Type: application/json" \
  -d '{
    "companies": [{
      "ticker": "AAPL",
      "fcf": 120000, "wacc": 0.09, "terminalGrowthRate": 0.025,
      "projectionYears": 5, "netDebt": 50000, "sharesOutstanding": 15500
    }]
  }'
```

### Poll job status (with partial results)

```bash
curl http://localhost:8083/valuations/{jobId}
```

### Stream results via SSE

```bash
curl http://localhost:8083/valuations/{jobId}/stream
```

### Cluster introspection

```bash
curl http://localhost:8083/internal/status
```

## Bully election protocol

Each node parses a numeric priority from its node id (`node-3` → 3, etc.).
Higher priority always wins.

| Step | Sender              | Receiver               | Behavior                                                                |
|------|---------------------|------------------------|-------------------------------------------------------------------------|
| 1    | Any node            | All higher-priority    | `POST /internal/election` — "I'm starting an election; respond if you outrank me." |
| 2    | Higher-priority node| The challenger         | Replies `outranks=true` and triggers its own election.                  |
| 3    | Winning node        | All other nodes        | `POST /internal/coordinator` — "I'm the new leader (epoch N)."          |
| 4    | Any follower (3s)   | Current leader         | `GET /internal/ping`. 2 consecutive misses → starts a new election.     |

If a higher-priority node enters the cluster late and finds a lower-priority
leader, it self-elects and broadcasts `COORDINATOR`; followers and the old
leader accept and step down (the bully rule: highest priority alive wins).

## Worker / leader endpoints

All on `/internal`:

| Endpoint            | Method | Caller              | Purpose                                  |
|---------------------|--------|---------------------|------------------------------------------|
| `/election`         | POST   | electing node       | Bully ELECTION challenge                 |
| `/coordinator`      | POST   | elected leader      | Bully COORDINATOR announcement           |
| `/ping`             | GET    | any node            | Cheap liveness check                     |
| `/heartbeat`        | GET    | leader              | Confirms a worker is still alive         |
| `/register`         | POST   | worker              | Joins the leader's heartbeat roster      |
| `/tasks/next`       | GET    | worker              | Pulls one task (or 204 if queue empty)   |
| `/results`          | POST   | worker              | Submits a completed DCF result           |
| `/status`           | GET    | anyone              | Whole-cluster snapshot                   |

## Configuration

| Property                              | Default | Description                                   |
|---------------------------------------|---------|-----------------------------------------------|
| `server.port`                         | 8080    | HTTP port for this node                       |
| `dfvs.node-id`                        | node-1  | Node id; numeric suffix = Bully priority      |
| `dfvs.cluster.nodes`                  | `node-1=localhost:8080` | Full topology. Default is single-node (matches `server.port`). Cluster scripts override at launch, e.g. `node-1=host:8081,node-2=host:8082,node-3=host:8083`. |
| `dfvs.election.ok-timeout-ms`         | 1500    | Wait for OK from a higher peer                |
| `dfvs.election.coordinator-timeout-ms`| 5000    | Wait for a COORDINATOR after OK               |
| `dfvs.election.leader-check-interval-ms` | 3000 | How often followers ping the leader           |
| `dfvs.election.leader-miss-threshold` | 2       | Consecutive ping misses → start election      |
| `dfvs.heartbeat.interval-ms`          | 3000    | Leader → worker heartbeat interval            |
| `dfvs.heartbeat.dead-threshold`       | 2       | Consecutive misses → worker DEAD              |
| `dfvs.task.timeout-ms`                | 30000   | Straggler timeout; duplicate enqueued         |
| `dfvs.worker.threads`                 | 2       | DCF threads per node                          |
| `dfvs.external-api.base-url`          | (empty) | If set, FinancialDataFetcher targets this URL instead of the co-located mock. Production deployments would point this at a real provider. |

## Project layout

```
src/main/java/com/dfvs/
├── DfvsApplication.java               # Spring Boot entry point
├── config/
│   └── ClusterConfig.java             # Parses dfvs.cluster.nodes, derives priorities
├── controller/
│   ├── ValuationController.java       # Client REST API (POST/GET/SSE)
│   ├── ElectionController.java        # /internal/election, /internal/coordinator, /internal/ping
│   ├── InternalController.java        # /internal/register, /tasks/next, /results, /heartbeat, /status
│   └── ExternalFinancialDataController.java  # Mock external Financial Data API
├── election/
│   ├── BullyElectionService.java      # The election state machine
│   └── ElectionMessages.java          # Wire records
├── model/                              # JPA entities + DTOs
├── repository/                         # Spring Data JPA repos
└── service/
    ├── DCFCalculator.java             # Stateless DCF math
    ├── DataStoreService.java          # Persistent storage + idempotency
    ├── FinancialDataFetcher.java      # External fetch + TTL cache
    ├── HeartbeatService.java          # Leader's view of worker health
    ├── LeaderService.java             # Task queue + reassignment + recovery
    ├── SseService.java                # SSE emitter management
    ├── WorkerService.java             # HTTP-based task pull loop
    └── dto/                            # Wire records
```

## Tests

```bash
# Unit + Spring controller tests (fast).
mvn test
```

Coverage (25 unit + controller tests):

- `DCFCalculatorTest` — DCF math, validation rules, monotonicity (9 tests)
- `BullyElectionLogicTest` — election protocol state transitions (6 tests)
- `ValuationControllerTest` — full Spring context, end-to-end job flow against
  a real Tomcat (5 tests)
- `TaskEntryOverridesTest` — analyst-override persistence and rehydration on
  failover recovery (3 tests)
- `ClusterConfigTest` — node-id priority parsing (2 tests)

```bash
# Multi-JVM integration test: spawns 3 java -jar processes, kills the leader,
# verifies the surviving nodes re-elect and resume serving jobs.
mvn package -DskipTests
mvn test -Ddfvs.integration=true -Dtest=ClusterFailoverIT
```

This last test is the proof that the system is actually distributed: each node
runs in its own OS process, all coordination is over HTTP, and a `SIGKILL` of
the leader does **not** crash anything else.

## H2 console

```
http://localhost:8081/h2-console
JDBC URL: jdbc:h2:file:./data/dfvsdb;AUTO_SERVER=TRUE
User:     sa
Password: (blank)
```

Useful queries:

```sql
SELECT * FROM JOBS;
SELECT JOB_ID, TICKER, ENTERPRISE_VALUE, IMPLIED_SHARE_PRICE FROM VALUATION_RESULTS;
SELECT ID, JOB_ID, TICKER, WORKER_ID, STATUS, UPDATED_AT FROM TASK_LOG ORDER BY UPDATED_AT;
```

The `TASK_LOG` table is the persistent record that backs leader recovery —
every assignment, reassignment, and completion is written there, and a newly
elected leader re-queues anything not yet terminal.

## Mock Financial Data API

Each node also hosts `GET /external/financials/{ticker}` returning simulated
income-statement / balance-sheet data for 8 tickers (AAPL, MSFT, GOOGL, AMZN,
TSLA, META, NVDA, JPM). The `FinancialDataFetcher` calls it over real HTTP
with retry + exponential backoff, derives DCF inputs (FCF, WACC, net debt)
from the raw data, and caches with TTL.

The mock controller is bundled in the JAR purely for the convenience of running
the demo as a single deliverable — it is **logically** an external system in
the architecture diagram. To point the cluster at a real or separately-hosted
Financial Data API, set the `dfvs.external-api.base-url` property on every
node:

```bash
java -jar target/distributed-financial-valuation-system-1.0.0.jar \
    --server.port=8081 --dfvs.node-id=node-1 \
    --dfvs.cluster.nodes=... \
    --dfvs.external-api.base-url=https://financial-data.example.com
```

When set, the embedded `/external/financials/{ticker}` endpoint is ignored and
all data fetches go to the configured URL.
