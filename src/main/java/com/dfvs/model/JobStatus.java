package com.dfvs.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the lifecycle of a valuation job with partial result support.
 */
@Entity
@Table(name = "jobs")
public class JobStatus {

    public enum Status {
        QUEUED, IN_PROGRESS, PARTIALLY_COMPLETED, COMPLETED, FAILED
    }

    @Id
    private String jobId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int totalCompanies;
    private int completedCompanies;

    @Transient // Results loaded separately via repository
    private List<ValuationResult> results = new ArrayList<>();

    private Instant submittedAt;
    private Instant completedAt;

    // Stores the ticker list as comma-separated for simplicity
    @Column(length = 4000)
    private String tickerList;

    public JobStatus() {
        this.submittedAt = Instant.now();
        this.status = Status.QUEUED;
    }

    public JobStatus(String jobId, List<String> tickers) {
        this();
        this.jobId = jobId;
        this.totalCompanies = tickers.size();
        this.completedCompanies = 0;
        this.tickerList = String.join(",", tickers);
    }

    public List<String> getTickers() {
        if (tickerList == null || tickerList.isEmpty()) return List.of();
        return List.of(tickerList.split(","));
    }

    // --- Getters & Setters ---

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getTotalCompanies() { return totalCompanies; }
    public void setTotalCompanies(int totalCompanies) { this.totalCompanies = totalCompanies; }

    public int getCompletedCompanies() { return completedCompanies; }
    public void setCompletedCompanies(int completedCompanies) { this.completedCompanies = completedCompanies; }

    public List<ValuationResult> getResults() { return results; }
    public void setResults(List<ValuationResult> results) { this.results = results; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getTickerList() { return tickerList; }
    public void setTickerList(String tickerList) { this.tickerList = tickerList; }
}
