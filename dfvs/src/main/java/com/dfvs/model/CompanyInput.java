package com.dfvs.model;

import jakarta.persistence.*;

/**
 * Input parameters for a single company's DCF valuation.
 * Analysts can provide overrides; otherwise the system fetches from external APIs.
 */
@Entity
@Table(name = "company_inputs")
public class CompanyInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ticker;

    // Free Cash Flow (most recent year, in millions)
    private Double fcf;

    // Weighted Average Cost of Capital (e.g. 0.10 = 10%)
    private Double wacc;

    // Terminal growth rate (e.g. 0.025 = 2.5%)
    private Double terminalGrowthRate;

    // Number of years to project
    private Integer projectionYears;

    // Net debt (total debt - cash, in millions)
    private Double netDebt;

    // Shares outstanding (in millions)
    private Double sharesOutstanding;

    public CompanyInput() {}

    public CompanyInput(String ticker) {
        this.ticker = ticker;
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public Double getFcf() { return fcf; }
    public void setFcf(Double fcf) { this.fcf = fcf; }

    public Double getWacc() { return wacc; }
    public void setWacc(Double wacc) { this.wacc = wacc; }

    public Double getTerminalGrowthRate() { return terminalGrowthRate; }
    public void setTerminalGrowthRate(Double terminalGrowthRate) { this.terminalGrowthRate = terminalGrowthRate; }

    public Integer getProjectionYears() { return projectionYears; }
    public void setProjectionYears(Integer projectionYears) { this.projectionYears = projectionYears; }

    public Double getNetDebt() { return netDebt; }
    public void setNetDebt(Double netDebt) { this.netDebt = netDebt; }

    public Double getSharesOutstanding() { return sharesOutstanding; }
    public void setSharesOutstanding(Double sharesOutstanding) { this.sharesOutstanding = sharesOutstanding; }

    @Override
    public String toString() {
        return "CompanyInput{ticker='" + ticker + "'}";
    }
}
