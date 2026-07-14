package com.dfvs.service;

import com.dfvs.model.ValuationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages SSE (Server-Sent Events) connections for streaming valuation results
 * to clients as they complete.
 */
@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);

    // jobId -> list of active SSE emitters
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Register a new SSE connection for a job.
     */
    public SseEmitter subscribe(String jobId) {
        SseEmitter emitter = new SseEmitter(0L); // No timeout
        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));

        log.info("SSE subscriber registered for job {}", jobId);
        return emitter;
    }

    /**
     * Push a valuation result to all subscribers of a job.
     */
    public void pushResult(String jobId, ValuationResult result) {
        List<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters == null || jobEmitters.isEmpty()) return;

        for (SseEmitter emitter : jobEmitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("result")
                    .data(result));
            } catch (IOException e) {
                removeEmitter(jobId, emitter);
            }
        }
    }

    /**
     * Send completion event and close all emitters for a job.
     */
    public void completeJob(String jobId) {
        List<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters == null) return;

        for (SseEmitter emitter : jobEmitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("complete")
                    .data("{\"status\":\"COMPLETED\",\"jobId\":\"" + jobId + "\"}"));
                emitter.complete();
            } catch (IOException e) {
                // Already closed
            }
        }
        emitters.remove(jobId);
        log.info("SSE stream completed for job {}", jobId);
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        List<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters != null) {
            jobEmitters.remove(emitter);
            if (jobEmitters.isEmpty()) {
                emitters.remove(jobId);
            }
        }
    }
}
