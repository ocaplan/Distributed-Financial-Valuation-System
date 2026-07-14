package com.dfvs.service;

import com.dfvs.model.*;
import com.dfvs.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistent store for jobs, results, task assignments, and cached financial data.
 * Supports leader recovery by providing the task log.
 */
@Service
public class DataStoreService {

    private static final Logger log = LoggerFactory.getLogger(DataStoreService.class);

    private final JobRepository jobRepo;
    private final ValuationResultRepository resultRepo;
    private final TaskEntryRepository taskRepo;
    private final FinancialDataCacheRepository cacheRepo;

    public DataStoreService(JobRepository jobRepo,
                            ValuationResultRepository resultRepo,
                            TaskEntryRepository taskRepo,
                            FinancialDataCacheRepository cacheRepo) {
        this.jobRepo = jobRepo;
        this.resultRepo = resultRepo;
        this.taskRepo = taskRepo;
        this.cacheRepo = cacheRepo;
    }

    // ========== Job Operations ==========

    @Transactional
    public JobStatus createJob(String jobId, List<String> tickers) {
        // Idempotency: check if job already exists
        Optional<JobStatus> existing = jobRepo.findById(jobId);
        if (existing.isPresent()) {
            log.info("Job {} already exists (idempotent check)", jobId);
            return existing.get();
        }

        JobStatus job = new JobStatus(jobId, tickers);
        jobRepo.save(job);
        log.info("Created job {} with {} companies", jobId, tickers.size());
        return job;
    }

    @Transactional
    public void updateJobStatus(String jobId, JobStatus.Status status) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            if (status == JobStatus.Status.COMPLETED || status == JobStatus.Status.FAILED) {
                job.setCompletedAt(Instant.now());
            }
            jobRepo.save(job);
            log.info("Job {} status updated to {}", jobId, status);
        });
    }

    /**
     * Atomically bumps completedCompanies via SQL UPDATE so concurrent result
     * submissions for the same job can't lose an increment, then re-fetches
     * the row and flips status to COMPLETED if we've now reached totalCompanies.
     * The status flip itself is idempotent — extra calls after the threshold
     * are safe.
     */
    @Transactional
    public void incrementCompleted(String jobId) {
        jobRepo.incrementCompletedCount(jobId);
        jobRepo.findById(jobId).ifPresent(job -> {
            if (job.getCompletedCompanies() >= job.getTotalCompanies()
                    && job.getStatus() != JobStatus.Status.COMPLETED
                    && job.getStatus() != JobStatus.Status.PARTIALLY_COMPLETED) {
                job.setStatus(JobStatus.Status.COMPLETED);
                job.setCompletedAt(Instant.now());
                jobRepo.save(job);
            }
        });
    }

    public Optional<JobStatus> getJobStatus(String jobId) {
        Optional<JobStatus> opt = jobRepo.findById(jobId);
        opt.ifPresent(job -> job.setResults(resultRepo.findByJobId(jobId)));
        return opt;
    }

    // ========== Result Operations (Idempotent) ==========

    @Transactional
    public boolean storeResult(String jobId, ValuationResult result) {
        // Idempotent: first result for (jobId, ticker) wins
        if (resultRepo.existsByJobIdAndTicker(jobId, result.getTicker())) {
            log.info("Result for {}/{} already exists, discarding duplicate", jobId, result.getTicker());
            return false;
        }
        result.setJobId(jobId);
        resultRepo.save(result);
        incrementCompleted(jobId);
        log.info("Stored result for {}/{}", jobId, result.getTicker());
        return true;
    }

    public List<ValuationResult> getResults(String jobId) {
        return resultRepo.findByJobId(jobId);
    }

    // ========== Task Log Operations ==========

    @Transactional
    public TaskEntry createTask(String jobId, String ticker) {
        TaskEntry task = new TaskEntry(jobId, ticker);
        return taskRepo.save(task);
    }

    /**
     * Create a task entry that also persists the analyst's per-company overrides
     * (FCF, WACC, etc.). If the leader dies before this task runs, the new
     * leader can rehydrate the same CompanyInput from the task log rather than
     * silently falling back to generic cached values.
     */
    @Transactional
    public TaskEntry createTaskWithOverrides(String jobId, CompanyInput input) {
        TaskEntry task = new TaskEntry(jobId, input.getTicker());
        task.setOverrides(input);
        return taskRepo.save(task);
    }

    @Transactional
    public void assignTask(Long taskId, String workerId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setWorkerId(workerId);
            task.setStatus(TaskEntry.TaskStatus.ASSIGNED);
            taskRepo.save(task);
        });
    }

    @Transactional
    public void updateTaskStatus(Long taskId, TaskEntry.TaskStatus status) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus(status);
            taskRepo.save(task);
        });
    }

    @Transactional
    public void reassignTask(Long taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setStatus(TaskEntry.TaskStatus.REASSIGNED);
            taskRepo.save(task);
        });
    }

    public List<TaskEntry> getIncompleteTasks() {
        return taskRepo.findByStatusIn(List.of(
            TaskEntry.TaskStatus.QUEUED,
            TaskEntry.TaskStatus.ASSIGNED,
            TaskEntry.TaskStatus.IN_PROGRESS
        ));
    }

    public List<TaskEntry> getTasksByWorker(String workerId, TaskEntry.TaskStatus status) {
        return taskRepo.findByWorkerIdAndStatus(workerId, status);
    }

    public List<TaskEntry> getTasksForJob(String jobId) {
        return taskRepo.findByJobId(jobId);
    }

    // ========== Financial Data Cache ==========

    public Optional<CachedFinancialData> getCachedData(String ticker) {
        Optional<CachedFinancialData> opt = cacheRepo.findById(ticker);
        // Return only if not stale
        return opt.filter(data -> !data.isStale());
    }

    @Transactional
    public void cacheFinancialData(CachedFinancialData data) {
        cacheRepo.save(data);
    }
}
