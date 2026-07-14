package com.dfvs.controller;

import com.dfvs.election.BullyElectionService;
import com.dfvs.service.HeartbeatService;
import com.dfvs.service.LeaderService;
import com.dfvs.service.WorkerService;
import com.dfvs.service.dto.RegisterWorkerRequest;
import com.dfvs.service.dto.SubmitResultRequest;
import com.dfvs.service.dto.TaskAssignment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inter-node endpoints. Election messages live in {@link ElectionController};
 * everything else (worker registration, task pull, result submit, heartbeat,
 * status) is here.
 *
 * Endpoints that require leader-only state (register / pull / submit) return
 * 503 with the current leader's id when this node isn't leader, so a stale
 * worker re-registers against the new leader on the next loop iteration.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final WorkerService workerService;
    private final BullyElectionService election;
    private final HeartbeatService heartbeatService;
    private final LeaderService leaderService;

    public InternalController(WorkerService workerService,
                               BullyElectionService election,
                               HeartbeatService heartbeatService,
                               LeaderService leaderService) {
        this.workerService = workerService;
        this.election = election;
        this.heartbeatService = heartbeatService;
        this.leaderService = leaderService;
    }

    /** Leader pings here to confirm this worker is alive. */
    @GetMapping("/heartbeat")
    public ResponseEntity<?> heartbeat() {
        boolean healthy = workerService.respondToHeartbeat();
        if (healthy) {
            return ResponseEntity.ok(Map.of(
                "workerId", workerService.getNodeId(),
                "status", "ALIVE",
                "epoch", election.getEpoch()
            ));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "UNHEALTHY"));
    }

    /** Worker -> Leader registration. Leader-only. */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterWorkerRequest req) {
        if (!election.isLeader()) {
            return notLeader();
        }
        heartbeatService.registerWorker(req.workerId(), req.hostPort());
        return ResponseEntity.ok(Map.of("registered", true, "leader", election.getNodeId()));
    }

    /** Worker pulls next task. Leader-only. */
    @GetMapping("/tasks/next")
    public ResponseEntity<?> nextTask(@RequestParam String workerId) {
        if (!election.isLeader()) {
            return notLeader();
        }
        TaskAssignment task = leaderService.pullTaskFor(workerId);
        if (task == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(task);
    }

    /** Worker submits a completed result. Leader-only. */
    @PostMapping("/results")
    public ResponseEntity<?> submitResult(@RequestBody SubmitResultRequest req) {
        if (!election.isLeader()) {
            return notLeader();
        }
        leaderService.receiveWorkerResult(req.workerId(), req.jobId(), req.result());
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    /** Cluster status for debugging / monitoring. */
    @GetMapping("/status")
    public ResponseEntity<?> clusterStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", election.getNodeId());
        status.put("role", election.getRole());
        status.put("isLeader", election.isLeader());
        status.put("leaderId", election.getLeaderId());
        status.put("epoch", election.getEpoch());
        status.put("workerRunning", workerService.isRunning());
        status.put("taskQueueSize", leaderService.getQueueSize());
        status.put("registeredWorkers", heartbeatService.getAllWorkers());
        return ResponseEntity.ok(status);
    }

    private ResponseEntity<?> notLeader() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not the leader");
        body.put("leaderId", election.getLeaderId());
        var addr = election.getLeaderAddress();
        if (addr != null) {
            body.put("leaderUrl", addr.baseUrl());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
