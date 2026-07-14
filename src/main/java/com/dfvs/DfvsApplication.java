package com.dfvs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Distributed Financial Valuation System.
 *
 * Each instance is a peer node in a Bully-elected cluster:
 *   - Hand-rolled Bully leader election over HTTP (no external coordination service)
 *   - Pull-based task queue distributed via HTTP from leader to workers
 *   - Heartbeat-based failure detection (2-strike policy) over HTTP
 *   - Automatic task reassignment on worker failure
 *   - Idempotent result storage; first result for (jobId, ticker) wins
 *   - SSE streaming of results to clients
 *   - Leader recovery from a persistent task log shared across the cluster
 *     (H2 file DB in AUTO_SERVER mode)
 *
 * Run a 3-node cluster locally via scripts/start-cluster.sh.
 */
@SpringBootApplication
public class DfvsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DfvsApplication.class, args);
    }
}
