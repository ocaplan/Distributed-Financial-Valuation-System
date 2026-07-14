package com.dfvs.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persistent task log entry. Used for tracking work assignment
 * and enabling leader recovery after failover.
 */
@Entity
@Table(name = "task_log")
public class TaskEntry {

    public enum TaskStatus {
        QUEUED, ASSIGNED, IN_PROGRESS, COMPLETED, FAILED, REASSIGNED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String jobId;
    private String ticker;
    private String workerId;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    // Per-job analyst overrides. Persisted so that if a leader dies after a
    // task is created but before it's been computed, the new leader can rebuild
    // the same CompanyInput it would have dispatched — without falling back to
    // generic cache/external-API data that loses the analyst's intent.
    @Column(name = "override_fcf")              private Double overrideFcf;
    @Column(name = "override_wacc")             private Double overrideWacc;
    @Column(name = "override_growth_rate")      private Double overrideTerminalGrowthRate;
    @Column(name = "override_projection_years") private Integer overrideProjectionYears;
    @Column(name = "override_net_debt")         private Double overrideNetDebt;
    @Column(name = "override_shares")           private Double overrideSharesOutstanding;

    public TaskEntry() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public TaskEntry(String jobId, String ticker) {
        this();
        this.jobId = jobId;
        this.ticker = ticker;
        this.status = TaskStatus.QUEUED;
    }

    /** Capture analyst-supplied override values (any of which may be null). */
    public void setOverrides(CompanyInput input) {
        this.overrideFcf = input.getFcf();
        this.overrideWacc = input.getWacc();
        this.overrideTerminalGrowthRate = input.getTerminalGrowthRate();
        this.overrideProjectionYears = input.getProjectionYears();
        this.overrideNetDebt = input.getNetDebt();
        this.overrideSharesOutstanding = input.getSharesOutstanding();
    }

    /** Rebuild a {@link CompanyInput} carrying just the persisted overrides. */
    public CompanyInput toOverrideOnlyInput() {
        CompanyInput input = new CompanyInput(ticker);
        input.setFcf(overrideFcf);
        input.setWacc(overrideWacc);
        input.setTerminalGrowthRate(overrideTerminalGrowthRate);
        input.setProjectionYears(overrideProjectionYears);
        input.setNetDebt(overrideNetDebt);
        input.setSharesOutstanding(overrideSharesOutstanding);
        return input;
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
