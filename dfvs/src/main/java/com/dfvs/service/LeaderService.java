package com.dfvs.service;

import com.dfvs.model.*;
import com.dfvs.service.dto.TaskAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core leader coordination service.
 *
 * Receives jobs from the API Gateway, distributes work via a pull-based task
 * queue, tracks progress, detects failures via HeartbeatService, reassigns
 * tasks, aggregates results, and pushes results to SSE subscribers.
 *
 * Only the node currently holding leadership exercises this service's
 * scheduling. Workers obtain tasks by HTTP-polling {@code /internal/tasks/next}
 * on the leader; the controller delegates to {@link #pullTaskFor(String)}.
 */
@Service
public class LeaderService {

    private static final Logger log = LoggerFactory.getLogger(LeaderService.class);

    private final DataStoreService dataStore;
    private final FinancialDataFetcher financialDataFetcher;
    private final HeartbeatService heartbeatService;
    private final SseService sseService;

    private final BlockingQueue<QueuedTask> taskQueue = new LinkedBlockingQueue<>();
    private final Map<Long, CompanyInput> taskInputMap = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> workerTasks = new ConcurrentHashMap<>();
    private final Map<Long, Instant> taskAssignedTime = new ConcurrentHashMap<>();
    // Serializes the read-modify-write of completedCompanies per jobId. Without
    // this, two concurrent result submissions can both read the same counter
    // and produce a lost increment, leaving the job stuck IN_PROGRESS.
    private final Map<String, Object> jobLocks = new ConcurrentHashMap<>();

    private ScheduledExecutorService stragglerMonitor;

    @Value("${dfvs.task.timeout-ms:30000}")
    private long taskTimeoutMs;

    public LeaderService(DataStoreService dataStore,
                         FinancialDataFetcher financialDataFetcher,
                         HeartbeatService heartbeatService,
                         SseService sseService) {
        this.dataStore = dataStore;
        this.financialDataFetcher = financialDataFetcher;
        this.heartbeatService = heartbeatService;
        this.sseService = sseService;

        heartbeatService.setFailureCallback(this::handleWorkerFailure);
    }

    // ============================================================
    // Leader lifecycle hooks
    // ============================================================

    public void startStragglerMonitor() {
        if (stragglerMonitor != null && !stragglerMonitor.isShutdown()) return;

        stragglerMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "straggler-monitor");
            t.setDaemon(true);
            return t;
        });

        stragglerMonitor.scheduleAtFixedRate(this::checkForStragglers,
            taskTimeoutMs, taskTimeoutMs / 2, TimeUnit.MILLISECONDS);
        log.info("Straggler monitor started (timeout={}ms)", taskTimeoutMs);
    }

    public void stopStragglerMonitor() {
        if (stragglerMonitor != null) {
            stragglerMonitor.shutdownNow();
            stragglerMonitor = null;
        }
        // Clear in-memory task state — recovery will rebuild from the persistent log.
        taskQueue.clear();
        taskInputMap.clear();
        workerTasks.clear();
        taskAssignedTime.clear();
    }

    // ============================================================
    // Public API used by controllers
    // ============================================================

    /**
     * Submit a new job for processing. Called from the gateway controller after
     * the request has been validated and persisted.
     */
    public void processJob(String jobId, List<CompanyInput> companies) {
        log.info("Leader processing job {} with {} companies", jobId, companies.size());
        dataStore.updateJobStatus(jobId, JobStatus.Status.IN_PROGRESS);

        int fetchFailures = 0;
        for (CompanyInput company : companies) {
            CompanyInput enriched = financialDataFetcher.fetchAndMerge(company);

            if (!hasMinimumData(enriched)) {
                log.error("Insufficient data for {}, marking failed in job {}", enriched.getTicker(), jobId);
                fetchFailures++;

                ValuationResult failedResult = new ValuationResult();
                failedResult.setJobId(jobId);
                failedResult.setTicker(enriched.getTicker());
                failedResult.setSuccess(false);
                failedResult.setErrorMessage("Failed to fetch financial data from external API");
                dataStore.storeResult(jobId, failedResult);
                sseService.pushResult(jobId, failedResult);
                continue;
            }

            // Persist the analyst's original overrides on the task log entry so
            // a successor leader can rehydrate them after a failover, instead
            // of silently swapping in generic cached values.
            TaskEntry taskEntry = dataStore.createTaskWithOverrides(jobId, company);
            taskInputMap.put(taskEntry.getId(), enriched);
            taskQueue.offer(new QueuedTask(taskEntry.getId(), jobId, enriched));
            log.info("Enqueued task {} for {}/{}", taskEntry.getId(), jobId, enriched.getTicker());
        }

        if (fetchFailures == companies.size()) {
            dataStore.updateJobStatus(jobId, JobStatus.Status.FAILED);
            sseService.completeJob(jobId);
            log.error("Job {} FAILED: could not fetch data for any company", jobId);
        }
    }

    /**
     * Hand a worker its next task, or return null if the queue is empty.
     * Records the assignment in the persistent task log.
     */
    public TaskAssignment pullTaskFor(String workerId) {
        QueuedTask item = taskQueue.poll();
        if (item == null) return null;

        dataStore.assignTask(item.taskEntryId(), workerId);
        workerTasks.computeIfAbsent(workerId, k -> ConcurrentHashMap.newKeySet())
                   .add(item.taskEntryId());
        taskAssignedTime.put(item.taskEntryId(), Instant.now());

        log.info("Worker {} pulled task {} ({}/{})",
                 workerId, item.taskEntryId(), item.jobId(), item.input().getTicker());
        return TaskAssignment.of(item.taskEntryId(), item.jobId(), item.input());
    }

    /**
     * Idempotent result handler. First result for (jobId, ticker) wins.
     * Synchronized per-job so concurrent submissions don't lose increments.
     */
    public void receiveWorkerResult(String workerId, String jobId, ValuationResult result) {
        log.info("Received result from worker {} for {}/{}", workerId, jobId, result.getTicker());

        synchronized (jobLocks.computeIfAbsent(jobId, k -> new Object())) {
            boolean stored = dataStore.storeResult(jobId, result);
            if (stored) {
                sseService.pushResult(jobId, result);

                Optional<JobStatus> jobOpt = dataStore.getJobStatus(jobId);
                if (jobOpt.isPresent()) {
                    JobStatus job = jobOpt.get();
                    if (job.getCompletedCompanies() >= job.getTotalCompanies()) {
                        boolean anyFailed = job.getResults().stream().anyMatch(r -> !r.isSuccess());
                        if (anyFailed) {
                            dataStore.updateJobStatus(jobId, JobStatus.Status.PARTIALLY_COMPLETED);
                            log.info("Job {} PARTIALLY_COMPLETED (some companies failed)", jobId);
                        } else {
                            dataStore.updateJobStatus(jobId, JobStatus.Status.COMPLETED);
                            log.info("Job {} COMPLETED", jobId);
                        }
                        sseService.completeJob(jobId);
                        jobLocks.remove(jobId);
                    }
                }
            }

            Set<Long> tasks = workerTasks.get(workerId);
            if (tasks != null) {
                tasks.removeIf(taskId -> {
                    CompanyInput input = taskInputMap.get(taskId);
                    if (input != null && input.getTicker().equals(result.getTicker())) {
                        taskAssignedTime.remove(taskId);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    /**
     * Heartbeat-failure callback: requeue everything assigned to the dead worker.
     */
    public void handleWorkerFailure(String workerId) {
        log.warn("Handling failure of worker {}", workerId);

        List<TaskEntry> incomplete = new ArrayList<>();
        incomplete.addAll(dataStore.getTasksByWorker(workerId, TaskEntry.TaskStatus.ASSIGNED));
        incomplete.addAll(dataStore.getTasksByWorker(workerId, TaskEntry.TaskStatus.IN_PROGRESS));

        for (TaskEntry task : incomplete) {
            boolean alreadyDone = dataStore.getResults(task.getJobId()).stream()
                .anyMatch(r -> r.getTicker().equals(task.getTicker()));
            if (alreadyDone) {
                log.info("Result already exists for {}/{}, skipping reassignment",
                         task.getJobId(), task.getTicker());
                continue;
            }

            dataStore.reassignTask(task.getId());

            CompanyInput input = taskInputMap.get(task.getId());
            if (input == null) {
                // Worker died before we could find the in-memory enrichment —
                // fall back to whatever overrides were persisted on the task log.
                CompanyInput original = task.toOverrideOnlyInput();
                input = financialDataFetcher.fetchAndMerge(original);
            }
            TaskEntry newTask = dataStore.createTaskWithOverrides(task.getJobId(), input);
            taskInputMap.put(newTask.getId(), input);
            taskQueue.offer(new QueuedTask(newTask.getId(), task.getJobId(), input));
            log.info("Reassigned {}/{} to queue (old task {} -> new task {})",
                     task.getJobId(), task.getTicker(), task.getId(), newTask.getId());

            taskAssignedTime.remove(task.getId());
        }

        workerTasks.remove(workerId);
    }

    private void checkForStragglers() {
        Instant cutoff = Instant.now().minusMillis(taskTimeoutMs);

        for (Map.Entry<Long, Instant> entry : taskAssignedTime.entrySet()) {
            if (!entry.getValue().isBefore(cutoff)) continue;

            Long taskId = entry.getKey();
            CompanyInput input = taskInputMap.get(taskId);
            if (input == null) continue;

            for (TaskEntry task : dataStore.getIncompleteTasks()) {
                if (!task.getId().equals(taskId)) continue;

                boolean alreadyDone = dataStore.getResults(task.getJobId()).stream()
                    .anyMatch(r -> r.getTicker().equals(task.getTicker()));
                if (!alreadyDone) {
                    TaskEntry dupTask = dataStore.createTask(task.getJobId(), task.getTicker());
                    taskInputMap.put(dupTask.getId(), input);
                    taskQueue.offer(new QueuedTask(dupTask.getId(), task.getJobId(), input));
                    log.warn("Straggler detected: task {} ({}/{}) exceeded {}ms; duplicate {} enqueued",
                             taskId, task.getJobId(), task.getTicker(), taskTimeoutMs, dupTask.getId());
                }
                taskAssignedTime.remove(taskId);
                break;
            }
        }
    }

    /**
     * Called on leader takeover. Rebuild in-memory state from the persistent
     * task log so any in-flight work the previous leader was tracking is
     * re-distributed.
     */
    public void recoverAndResume() {
        log.info("New leader recovering state from task log...");
        List<TaskEntry> incomplete = dataStore.getIncompleteTasks();
        int requeued = 0;

        for (TaskEntry task : incomplete) {
            boolean alreadyDone = dataStore.getResults(task.getJobId()).stream()
                .anyMatch(r -> r.getTicker().equals(task.getTicker()));
            if (alreadyDone) {
                dataStore.reassignTask(task.getId());
                continue;
            }

            // Rehydrate the analyst's original overrides from the task log,
            // then let the fetcher fill in any unspecified fields from cache /
            // the external API. This preserves user intent across a leader crash.
            CompanyInput original = task.toOverrideOnlyInput();
            CompanyInput input = financialDataFetcher.fetchAndMerge(original);
            TaskEntry newTask = dataStore.createTaskWithOverrides(task.getJobId(), original);
            taskInputMap.put(newTask.getId(), input);
            taskQueue.offer(new QueuedTask(newTask.getId(), task.getJobId(), input));
            requeued++;
            log.info("Recovery: re-queued {}/{}", task.getJobId(), task.getTicker());

            dataStore.reassignTask(task.getId());
        }

        log.info("Leader recovery complete. {} tasks re-queued out of {} incomplete.",
                 requeued, incomplete.size());
    }

    private boolean hasMinimumData(CompanyInput input) {
        return input.getFcf() != null
            && input.getWacc() != null
            && input.getSharesOutstanding() != null
            && input.getSharesOutstanding() > 0;
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    /** In-memory queue entry. Not exposed over HTTP — use {@link TaskAssignment}. */
    private record QueuedTask(Long taskEntryId, String jobId, CompanyInput input) {}
}
