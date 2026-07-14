package com.dfvs.election;

import com.dfvs.config.ClusterConfig;
import com.dfvs.config.ClusterConfig.NodeAddress;
import com.dfvs.election.ElectionMessages.*;
import com.dfvs.service.HeartbeatService;
import com.dfvs.service.LeaderService;
import com.dfvs.service.WorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hand-rolled Bully algorithm leader election over HTTP.
 *
 * On startup, and whenever the current leader appears unreachable, each node
 * begins an election:
 *   1. Send ELECTION to every peer with a strictly higher priority (numeric suffix of node id).
 *   2. If any higher peer responds within a short timeout, wait for that peer's
 *      COORDINATOR message. If none arrives within a longer timeout, retry.
 *   3. If no higher peer responds, declare self LEADER and broadcast COORDINATOR
 *      to every other node.
 *
 * The same election logic handles startup (no leader yet) and failover (previous
 * leader unreachable). Epochs are incremented on every leadership transition so
 * stale messages can be rejected.
 */
@Service
public class BullyElectionService {

    private static final Logger log = LoggerFactory.getLogger(BullyElectionService.class);

    public enum Role { FOLLOWER, ELECTING, LEADER }

    private final ClusterConfig cluster;
    private final LeaderService leaderService;
    private final HeartbeatService heartbeatService;
    private final WorkerService workerService;

    @Value("${dfvs.election.ok-timeout-ms:1500}")
    private long okTimeoutMs;

    @Value("${dfvs.election.coordinator-timeout-ms:5000}")
    private long coordinatorTimeoutMs;

    @Value("${dfvs.election.leader-check-interval-ms:3000}")
    private long leaderCheckIntervalMs;

    @Value("${dfvs.election.leader-miss-threshold:2}")
    private int leaderMissThreshold;

    private final AtomicReference<Role> role = new AtomicReference<>(Role.FOLLOWER);
    private final AtomicReference<String> leaderId = new AtomicReference<>(null);
    private final AtomicInteger epoch = new AtomicInteger(0);
    private final AtomicInteger leaderMisses = new AtomicInteger(0);
    private final AtomicBoolean electionInProgress = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ExecutorService electionExecutor;
    private final RestClient http = RestClient.builder()
        .requestFactory(buildShortTimeoutFactory())
        .build();

    public BullyElectionService(ClusterConfig cluster,
                                 LeaderService leaderService,
                                 HeartbeatService heartbeatService,
                                 WorkerService workerService) {
        this.cluster = cluster;
        this.leaderService = leaderService;
        this.heartbeatService = heartbeatService;
        this.workerService = workerService;
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory buildShortTimeoutFactory() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofMillis(1500).toMillis());
        f.setReadTimeout((int) Duration.ofMillis(1500).toMillis());
        return f;
    }

    /**
     * Begin election once the HTTP server is fully up so peers can call us.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "election-scheduler");
            t.setDaemon(true);
            return t;
        });
        electionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "election-executor");
            t.setDaemon(true);
            return t;
        });

        log.info("Node {} (priority {}) starting initial election",
                 cluster.getSelfNodeId(), cluster.getSelfPriority());
        triggerElection("startup");

        scheduler.scheduleAtFixedRate(this::monitorLeader,
            leaderCheckIntervalMs, leaderCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (electionExecutor != null) electionExecutor.shutdownNow();
        if (role.get() == Role.LEADER) {
            relinquishLeadership();
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    public boolean isLeader() {
        return role.get() == Role.LEADER;
    }

    public Role getRole() {
        return role.get();
    }

    public String getLeaderId() {
        return leaderId.get();
    }

    public NodeAddress getLeaderAddress() {
        String id = leaderId.get();
        return id != null ? cluster.getNode(id) : null;
    }

    public int getEpoch() {
        return epoch.get();
    }

    public String getNodeId() {
        return cluster.getSelfNodeId();
    }

    // ============================================================
    // Election triggers
    // ============================================================

    private void triggerElection(String reason) {
        if (electionInProgress.compareAndSet(false, true)) {
            electionExecutor.submit(() -> {
                try {
                    log.info("Election triggered ({})", reason);
                    runElection();
                } catch (Throwable t) {
                    log.error("Election failed: {}", t.getMessage(), t);
                } finally {
                    electionInProgress.set(false);
                }
            });
        } else {
            log.debug("Election already in progress; skipping trigger ({})", reason);
        }
    }

    /**
     * Core Bully algorithm:
     *  1. Send ELECTION to every higher-priority peer.
     *  2. If any responds, await COORDINATOR; otherwise self-elect.
     */
    private void runElection() {
        role.set(Role.ELECTING);

        List<NodeAddress> higher = cluster.getHigherPeers();
        if (higher.isEmpty()) {
            log.info("No higher-priority peers; self-electing");
            assumeLeadership();
            return;
        }

        boolean anyOutranks = false;
        for (NodeAddress peer : higher) {
            try {
                ElectionResponse resp = http.post()
                    .uri(peer.baseUrl() + "/internal/election")
                    .body(new ElectionRequest(cluster.getSelfNodeId(), cluster.getSelfPriority()))
                    .retrieve()
                    .body(ElectionResponse.class);
                if (resp != null && resp.outranks()) {
                    log.info("Peer {} outranks us; will await COORDINATOR", peer.id());
                    anyOutranks = true;
                }
            } catch (ResourceAccessException | RestClientResponseException e) {
                log.debug("ELECTION to {} failed: {}", peer.id(), e.getMessage());
            } catch (Exception e) {
                log.debug("ELECTION to {} failed: {}", peer.id(), e.toString());
            }
        }

        if (!anyOutranks) {
            log.info("No higher peer responded; self-electing");
            assumeLeadership();
            return;
        }

        // Wait for a COORDINATOR message. If none arrives, restart election.
        long deadline = System.currentTimeMillis() + coordinatorTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (leaderId.get() != null && !leaderId.get().equals(cluster.getSelfNodeId())) {
                log.info("COORDINATOR received: leader is {}", leaderId.get());
                return;
            }
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }

        log.warn("No COORDINATOR received within {}ms; restarting election", coordinatorTimeoutMs);
        // Recurse via trigger so we keep the in-progress flag accounting clean
        role.set(Role.FOLLOWER);
        electionInProgress.set(false);
        triggerElection("coordinator-timeout");
    }

    private void assumeLeadership() {
        int newEpoch = epoch.incrementAndGet();
        role.set(Role.LEADER);
        leaderId.set(cluster.getSelfNodeId());
        leaderMisses.set(0);

        log.info("=== Node {} is LEADER (epoch {}) ===", cluster.getSelfNodeId(), newEpoch);

        // Tell every other node we won.
        CoordinatorMessage msg = new CoordinatorMessage(
            cluster.getSelfNodeId(), cluster.getSelfPriority(), newEpoch);
        for (NodeAddress peer : cluster.getPeers()) {
            try {
                http.post()
                    .uri(peer.baseUrl() + "/internal/coordinator")
                    .body(msg)
                    .retrieve()
                    .body(AckResponse.class);
            } catch (Exception e) {
                log.debug("COORDINATOR send to {} failed (peer may be down): {}", peer.id(), e.getMessage());
            }
        }

        // Activate leader services.
        leaderService.startStragglerMonitor();
        heartbeatService.startHeartbeatMonitoring();
        leaderService.recoverAndResume();

        // The leader is also a worker — register self so the leader heartbeats us
        // and so this node's worker threads start pulling tasks.
        workerService.registerWithLeader(cluster.getSelf());
    }

    private void relinquishLeadership() {
        log.info("Node {} relinquishing leadership", cluster.getSelfNodeId());
        role.set(Role.FOLLOWER);
        leaderService.stopStragglerMonitor();
        heartbeatService.stopHeartbeatMonitoring();
    }

    // ============================================================
    // Incoming protocol messages (called by ElectionController)
    // ============================================================

    public ElectionResponse onElectionMessage(ElectionRequest req) {
        boolean outranks = cluster.getSelfPriority() > req.fromPriority();
        log.info("Received ELECTION from {} (priority {}); we are priority {} -> outranks={}",
                 req.fromNodeId(), req.fromPriority(), cluster.getSelfPriority(), outranks);

        if (outranks) {
            // Higher node received an ELECTION from a lower node — start its own election.
            triggerElection("received-election-from-lower-" + req.fromNodeId());
        }
        return new ElectionResponse(cluster.getSelfNodeId(), cluster.getSelfPriority(), outranks);
    }

    public AckResponse onCoordinatorMessage(CoordinatorMessage msg) {
        log.info("Received COORDINATOR from {} (priority {}, epoch {})",
                 msg.leaderNodeId(), msg.leaderPriority(), msg.epoch());

        // A lower-priority node claiming leadership (e.g. it timed out waiting for
        // our OK during a slow startup or partition). The bully algorithm says the
        // highest-priority alive node wins, so challenge with our own election.
        if (msg.leaderPriority() < cluster.getSelfPriority()) {
            log.warn("COORDINATOR from lower-priority node {} - challenging", msg.leaderNodeId());
            triggerElection("lower-priority-leader-claim");
            return new AckResponse(false);
        }

        // A higher-priority node trumps any current leader unconditionally.
        // For same-priority resends (same id), require a non-decreasing epoch.
        boolean sameLeader = msg.leaderNodeId().equals(leaderId.get());
        if (sameLeader && msg.epoch() < epoch.get()) {
            log.warn("Rejecting stale COORDINATOR resend (msg epoch {} < current {})",
                     msg.epoch(), epoch.get());
            return new AckResponse(false);
        }

        // If we were leader, step down for the higher-priority claimant.
        if (role.get() == Role.LEADER && !msg.leaderNodeId().equals(cluster.getSelfNodeId())) {
            relinquishLeadership();
        }

        leaderId.set(msg.leaderNodeId());
        // Track the highest epoch we've ever seen so our own self-election bumps it past.
        if (msg.epoch() > epoch.get()) {
            epoch.set(msg.epoch());
        }
        leaderMisses.set(0);
        if (role.get() != Role.LEADER) {
            role.set(Role.FOLLOWER);
            workerService.registerWithLeader(cluster.getNode(msg.leaderNodeId()));
        }
        return new AckResponse(true);
    }

    // ============================================================
    // Leader liveness monitor (followers only)
    // ============================================================

    private void monitorLeader() {
        if (role.get() == Role.LEADER || role.get() == Role.ELECTING) return;
        if (leaderId.get() == null) {
            triggerElection("no-known-leader");
            return;
        }

        NodeAddress leader = cluster.getNode(leaderId.get());
        if (leader == null) {
            triggerElection("leader-not-in-cluster");
            return;
        }

        boolean alive;
        try {
            http.get().uri(leader.baseUrl() + "/internal/ping").retrieve().toBodilessEntity();
            alive = true;
        } catch (Exception e) {
            alive = false;
        }

        if (alive) {
            leaderMisses.set(0);
        } else {
            int misses = leaderMisses.incrementAndGet();
            log.warn("Leader {} missed health check ({}/{})", leaderId.get(), misses, leaderMissThreshold);
            if (misses >= leaderMissThreshold) {
                leaderId.set(null);
                leaderMisses.set(0);
                triggerElection("leader-unreachable");
            }
        }
    }
}
