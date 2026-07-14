package com.dfvs.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Cached financial data retrieved from external APIs.
 * TTL-based: entries older than the TTL are considered stale.
 */
@Entity
@Table(name = "financial_data_cache")
public class CachedFinancialData {

    @Id
    private String ticker;

    private double fcf;
    private double wacc;
    private double terminalGrowthRate;
    private int projectionYears;
    private double netDebt;
    private double sharesOutstanding;

    private Instant fetchedAt;

    // Default TTL: 24 hours
    private static final long TTL_SECONDS = 86400;

    public CachedFinancialData() {
        this.fetchedAt = Instant.now();
    }

    public boolean isStale() {
        return Instant.now().isAfter(fetchedAt.plusSeconds(TTL_SECONDS));
    }

    /**
     * Merge cached data into a CompanyInput, preserving any user-provided overrides.
     */
    public CompanyInput mergeInto(CompanyInput input) {
        if (input.getFcf() == null) input.setFcf(this.fcf);
        if (input.getWacc() == null) input.setWacc(this.wacc);
        if (input.getTerminalGrowthRate() == null) input.setTerminalGrowthRate(this.terminalGrowthRate);
        if (input.getProjectionYears() == null) input.setProjectionYears(this.projectionYears);
        if (input.getNetDebt() == null) input.setNetDebt(this.netDebt);
        if (input.getSharesOutstanding() == null) input.setSharesOutstanding(this.sharesOutstanding);
        return input;
    }

    // --- Getters & Setters ---

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public double getFcf() { return fcf; }
    public void setFcf(double fcf) { this.fcf = fcf; }

    public double getWacc() { return wacc; }
    public void setWacc(double wacc) { this.wacc = wacc; }

    public double getTerminalGrowthRate() { return terminalGrowthRate; }
    public void setTerminalGrowthRate(double terminalGrowthRate) { this.terminalGrowthRate = terminalGrowthRate; }

    public int getProjectionYears() { return projectionYears; }
    public void setProjectionYears(int projectionYears) { this.projectionYears = projectionYears; }

    public double getNetDebt() { return netDebt; }
    public void setNetDebt(double netDebt) { this.netDebt = netDebt; }

    public double getSharesOutstanding() { return sharesOutstanding; }
    public void setSharesOutstanding(double sharesOutstanding) { this.sharesOutstanding = sharesOutstanding; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
