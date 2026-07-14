package com.dfvs.service;

import com.dfvs.config.ClusterConfig;
import com.dfvs.config.ClusterConfig.NodeAddress;
import com.dfvs.model.CompanyInput;
import com.dfvs.model.ValuationResult;
import com.dfvs.service.dto.RegisterWorkerRequest;
import com.dfvs.service.dto.SubmitResultRequest;
import com.dfvs.service.dto.TaskAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker service. Each node always runs the worker loop in addition to whatever
 * coordination role it currently holds. Workers pull tasks from the current
 * leader over HTTP and submit results back the same way.
 *
 * The election service is responsible for telling the worker which node is the
 * current leader (via {@link #registerWithLeader(NodeAddress)}); the worker loop
 * idles until a leader is known and re-targets transparently if the leader
 * changes.
 */
@Service
public class WorkerService {

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    private final DCFCalculator dcfCalculator;
    private final ClusterConfig cluster;

    @Value("${dfvs.worker.threads:3}")
    private int workerThreads;

    @Value("${dfvs.worker.idle-poll-ms:500}")
    private long idlePollMs;

    @Value("${dfvs.worker.simulated-work-min-ms:300}")
    private long simulatedWorkMinMs;

    @Value("${dfvs.worker.simulated-work-max-ms:900}")
    private long simulatedWorkMaxMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<NodeAddress> currentLeader = new AtomicReference<>();
    private ExecutorService workerPool;

    private final RestClient http = RestClient.builder()
        .requestFactory(buildShortTimeoutFactory())
        .build();

    public WorkerService(DCFCalculator dcfCalculator, ClusterConfig cluster) {
        this.dcfCalculator = dcfCalculator;
        this.cluster = cluster;
    }

    private static SimpleClientHttpRequestFactory buildShortTimeoutFactory() {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofMillis(2000).toMillis());
        f.setReadTimeout((int) Duration.ofMillis(2000).toMillis());
        return f;
    }

    /**
     * Spin up worker threads. Idempotent — safe to call from multiple lifecycle hooks.
     */
    public synchronized void startWorking() {
        if (running.getAndSet(true)) return;

        workerPool = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "dcf-worker-" + cluster.getSelfNodeId());
            t.setDaemon(true);
            return t;
        });
        // All threads on this node identify themselves to the leader with the
        // node id, not a per-thread id — this is what the heartbeat tracks, so
        // a node-level death needs to reclaim tasks by node id, not by an
        // internal thread label the leader never learns about.
        String workerId = cluster.getSelfNodeId();
        for (int i = 0; i < workerThreads; i++) {
            String threadLabel = workerId + "-thread-" + i;
            workerPool.submit(() -> workerLoop(workerId, threadLabel));
        }
        log.info("Worker service started with {} threads", workerThreads);
    }

    public synchronized void stopWorking() {
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdownNow();
            workerPool = null;
        }
        log.info("Worker service stopped");
    }

    @PreDestroy
    public void shutdown() {
        stopWorking();
    }

    /**
     * Called by the election service when this node learns of a leader (including
     * itself). Also performs the HTTP registration so the leader can heartbeat us.
     */
    public void registerWithLeader(NodeAddress leader) {
        if (leader == null) return;
        NodeAddress previous = currentLeader.getAndSet(leader);
        if (previous == null || !previous.id().equals(leader.id())) {
            log.info("Worker re-targeting leader: {} -> {}",
                     previous == null ? "(none)" : previous.id(), leader.id());
        }

        NodeAddress self = cluster.getSelf();
        try {
            http.post()
                .uri(leader.baseUrl() + "/internal/register")
                .body(new RegisterWorkerRequest(self.id(), self.host(), self.port()))
                .retrieve()
                .toBodilessEntity();
            log.info("Registered with leader {} at {}", leader.id(), leader.baseUrl());
        } catch (Exception e) {
            log.warn("Failed to register with leader {}: {}", leader.id(), e.getMessage());
        }

        // Ensure worker loop is running; idempotent.
        startWorking();
    }

    private void workerLoop(String workerId, String threadLabel) {
        log.info("Worker thread {} started", threadLabel);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            NodeAddress leader = currentLeader.get();
            if (leader == null) {
                sleep(idlePollMs);
                continue;
            }
            try {
                TaskAssignment task = pullTask(leader, workerId);
                if (task == null) {
                    sleep(idlePollMs);
                    continue;
                }

                CompanyInput input = task.toCompanyInput();
                log.info("[{}] computing DCF for {}/{}", threadLabel, task.jobId(), input.getTicker());
                ValuationResult result = dcfCalculator.compute(task.jobId(), input);

                // Small randomized delay to simulate per-company compute variance.
                long delay = ThreadLocalRandom.current()
                    .nextLong(simulatedWorkMinMs, Math.max(simulatedWorkMinMs + 1, simulatedWorkMaxMs));
                sleep(delay);

                submitResult(leader, workerId, task.jobId(), result);
                log.info("[{}] completed {}/{}", threadLabel, task.jobId(), input.getTicker());

            } catch (Exception e) {
                log.warn("[{}] loop error (will retry): {}", threadLabel, e.getMessage());
                sleep(idlePollMs);
            }
        }
        log.info("Worker thread {} stopped", threadLabel);
    }

    private TaskAssignment pullTask(NodeAddress leader, String workerId) {
        try {
            return http.get()
                .uri(leader.baseUrl() + "/internal/tasks/next?workerId=" + workerId)
                .retrieve()
                .body(TaskAssignment.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().value() == 204) return null;
            log.debug("pullTask got HTTP {}: {}", e.getStatusCode(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("pullTask transport error: {}", e.getMessage());
            return null;
        }
    }

    private void submitResult(NodeAddress leader, String workerId, String jobId, ValuationResult result) {
        try {
            http.post()
                .uri(leader.baseUrl() + "/internal/results")
                .body(new SubmitResultRequest(workerId, jobId, result))
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to submit result for {}/{} to leader {}: {}",
                     jobId, result.getTicker(), leader.id(), e.getMessage());
        }
    }

    /** Workers respond to leader heartbeats — returns true if this node is processing tasks. */
    public boolean respondToHeartbeat() {
        return running.get();
    }

    public String getNodeId() {
        return cluster.getSelfNodeId();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
