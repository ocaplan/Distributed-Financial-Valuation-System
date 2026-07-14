package com.dfvs.controller;

import com.dfvs.election.BullyElectionService;
import com.dfvs.model.*;
import com.dfvs.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Client API Gateway - REST controller exposing all 3 endpoints:
 *   POST /valuations         - Submit a portfolio valuation request
 *   GET  /valuations/{jobId} - Poll job status with partial results
 *   GET  /valuations/{jobId}/stream - SSE stream of results as they complete
 */
@RestController
@RequestMapping("/valuations")
public class ValuationController {

    private static final Logger log = LoggerFactory.getLogger(ValuationController.class);

    private final DataStoreService dataStore;
    private final LeaderService leaderService;
    private final BullyElectionService electionService;
    private final SseService sseService;

    public ValuationController(DataStoreService dataStore,
                                LeaderService leaderService,
                                BullyElectionService electionService,
                                SseService sseService) {
        this.dataStore = dataStore;
        this.leaderService = leaderService;
        this.electionService = electionService;
        this.sseService = sseService;
    }

    /**
     * POST /valuations
     * Submit a portfolio valuation request.
     * Returns 202 Accepted with the jobId.
     */
    @PostMapping
    public ResponseEntity<?> submitValuation(@RequestBody ValuationRequest request) {
        // Validate request
        if (request.getCompanies() == null || request.getCompanies().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Company list cannot be empty"));
        }

        // Validate tickers
        for (CompanyInput company : request.getCompanies()) {
            if (company.getTicker() == null || company.getTicker().isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "All companies must have a ticker symbol"));
            }
            String ticker = company.getTicker().toUpperCase().trim();
            if (!ticker.matches("[A-Z]{1,5}")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid ticker format: " + ticker + " (must be 1-5 uppercase letters)"));
            }
            // Validate override values if provided
            if (company.getWacc() != null && (company.getWacc() <= 0 || company.getWacc() >= 1)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "WACC must be between 0 and 1 for " + ticker));
            }
        }

        // Only the elected leader accepts jobs. Tell the client where to retry.
        if (!electionService.isLeader()) {
            var leader = electionService.getLeaderAddress();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "This node is not the leader");
            body.put("leaderId", electionService.getLeaderId());
            if (leader != null) body.put("leaderUrl", leader.baseUrl());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2")
                .body(body);
        }

        // Generate job ID (idempotency key)
        String jobId = UUID.randomUUID().toString().substring(0, 8);

        // Extract tickers for the job record
        List<String> tickers = request.getCompanies().stream()
            .map(c -> c.getTicker().toUpperCase())
            .collect(Collectors.toList());

        // Persist initial job record
        dataStore.createJob(jobId, tickers);

        // Forward to leader for processing
        leaderService.processJob(jobId, request.getCompanies());

        log.info("Job {} submitted with {} companies: {}", jobId, tickers.size(), tickers);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(Map.of(
                "jobId", jobId,
                "status", "QUEUED",
                "totalCompanies", tickers.size(),
                "tickers", tickers
            ));
    }

    /**
     * GET /valuations/{jobId}
     * Poll job status with partial results.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        Optional<JobStatus> jobOpt = dataStore.getJobStatus(jobId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Job not found: " + jobId));
        }

        JobStatus job = jobOpt.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.getJobId());
        response.put("status", job.getStatus());
        response.put("totalCompanies", job.getTotalCompanies());
        response.put("completedCompanies", job.getCompletedCompanies());
        response.put("submittedAt", job.getSubmittedAt());
        response.put("completedAt", job.getCompletedAt());
        response.put("results", job.getResults());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /valuations/{jobId}/stream
     * SSE stream that pushes each ValuationResult as it completes.
     */
    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamResults(@PathVariable String jobId) {
        Optional<JobStatus> jobOpt = dataStore.getJobStatus(jobId);
        if (jobOpt.isEmpty()) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("Job not found: " + jobId));
            return emitter;
        }

        JobStatus job = jobOpt.get();
        SseEmitter emitter = sseService.subscribe(jobId);

        // If job is already completed, send all results and close
        if (job.getStatus() == JobStatus.Status.COMPLETED) {
            try {
                for (ValuationResult result : job.getResults()) {
                    emitter.send(SseEmitter.event().name("result").data(result));
                }
                emitter.send(SseEmitter.event().name("complete")
                    .data("{\"status\":\"COMPLETED\",\"jobId\":\"" + jobId + "\"}"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }

        return emitter;
    }
}
