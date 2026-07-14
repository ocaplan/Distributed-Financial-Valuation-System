package com.dfvs.service;

import com.dfvs.model.WorkerInfo;
import com.dfvs.model.WorkerInfo.WorkerHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Worker health monitoring with 2-strike failure detection.
 * The leader runs this service to periodically ping workers.
 *
 * - 1 missed heartbeat → UNRESPONSIVE (no action yet)
 * - 2 consecutive misses → DEAD (tasks reassigned)
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${dfvs.heartbeat.interval-ms:3000}")
    private long heartbeatIntervalMs;

    @Value("${dfvs.heartbeat.dead-threshold:2}")
    private int deadThreshold;

    // Callback for when a worker is declared DEAD
    private WorkerFailureCallback failureCallback;

    public interface WorkerFailureCallback {
        void onWorkerDead(String workerId);
    }

    public void setFailureCallback(WorkerFailureCallback callback) {
        this.failureCallback = callback;
    }

    /**
     * Register a worker node for heartbeat monitoring.
     */
    public void registerWorker(String workerId, String address) {
        workers.put(workerId, new WorkerInfo(workerId, address));
        log.info("Registered worker {} at {}", workerId, address);
    }

    /**
     * Remove a worker from monitoring (e.g., after confirmed death).
     */
    public void deregisterWorker(String workerId) {
        workers.remove(workerId);
        log.info("Deregistered worker {}", workerId);
    }

    /**
     * Start periodic heartbeat monitoring. Only the leader should call this.
     */
    public void startHeartbeatMonitoring() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return; // Already running
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::checkAllWorkers,
            heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Heartbeat monitoring started (interval={}ms, deadThreshold={})",
                 heartbeatIntervalMs, deadThreshold);
    }

    /**
     * Stop heartbeat monitoring and forget all registered workers.
     * Called when this node loses leadership — the next leader will receive
     * fresh registrations from the same workers.
     */
    public void stopHeartbeatMonitoring() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            log.info("Heartbeat monitoring stopped");
        }
        workers.clear();
    }

    private void checkAllWorkers() {
        for (WorkerInfo worker : workers.values()) {
            if (worker.getHealth() == WorkerHealth.DEAD) continue;

            boolean alive = pingWorker(worker);
            if (alive) {
                worker.recordHeartbeat(0);
            } else {
                WorkerHealth previousHealth = worker.getHealth();
                worker.recordMiss(deadThreshold);
                log.warn("Worker {} missed heartbeat (consecutive={}), status: {} → {}",
                    worker.getWorkerId(), worker.getConsecutiveMisses(),
                    previousHealth, worker.getHealth());

                if (worker.getHealth() == WorkerHealth.DEAD && failureCallback != null) {
                    log.error("Worker {} declared DEAD after {} missed heartbeats",
                              worker.getWorkerId(), deadThreshold);
                    failureCallback.onWorkerDead(worker.getWorkerId());
                }
            }
        }
    }

    private boolean pingWorker(WorkerInfo worker) {
        try {
            String url = "http://" + worker.getAddress() + "/internal/heartbeat";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get list of alive worker IDs.
     */
    public List<String> getAliveWorkers() {
        return workers.values().stream()
            .filter(w -> w.getHealth() == WorkerHealth.ALIVE)
            .map(WorkerInfo::getWorkerId)
            .collect(Collectors.toList());
    }

    /**
     * Get the address of a worker by ID.
     */
    public String getWorkerAddress(String workerId) {
        WorkerInfo info = workers.get(workerId);
        return info != null ? info.getAddress() : null;
    }

    public WorkerHealth getWorkerHealth(String workerId) {
        WorkerInfo info = workers.get(workerId);
        return info != null ? info.getHealth() : null;
    }

    public Collection<WorkerInfo> getAllWorkers() {
        return Collections.unmodifiableCollection(workers.values());
    }
}
