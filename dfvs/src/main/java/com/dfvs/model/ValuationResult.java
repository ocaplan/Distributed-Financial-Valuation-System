package com.dfvs.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * The output of a DCF valuation for a single company.
 */
@Entity
@Table(name = "valuation_results",
       uniqueConstraints = @UniqueConstraint(columnNames = {"jobId", "ticker"}))
public class ValuationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String jobId;
    private String ticker;

    private double enterpriseValue;   // PV of FCFs + terminal value
    private double equityValue;       // enterprise value - net debt
    private double impliedSharePrice; // equity value / shares outstanding
    private double terminalValue;     // terminal value component

    private Instant timestamp;

    // If the calculation failed, this holds the error message
    private String errorMessage;
    private boolean success = true;

    public ValuationResult() {
        this.timestamp = Instant.now();
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public double getEnterpriseValue() { return enterpriseValue; }
    public void setEnterpriseValue(double enterpriseValue) { this.enterpriseValue = enterpriseValue; }

    public double getEquityValue() { return equityValue; }
    public void setEquityValue(double equityValue) { this.equityValue = equityValue; }

    public double getImpliedSharePrice() { return impliedSharePrice; }
    public void setImpliedSharePrice(double impliedSharePrice) { this.impliedSharePrice = impliedSharePrice; }

    public double getTerminalValue() { return terminalValue; }
    public void setTerminalValue(double terminalValue) { this.terminalValue = terminalValue; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    @Override
    public String toString() {
        return "ValuationResult{ticker='" + ticker + "', impliedSharePrice=" + impliedSharePrice + "}";
    }
}
