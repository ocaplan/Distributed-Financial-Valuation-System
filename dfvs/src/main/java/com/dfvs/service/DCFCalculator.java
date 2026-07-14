package com.dfvs.service;

import com.dfvs.model.CompanyInput;
import com.dfvs.model.ValuationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stateless DCF (Discounted Cash Flow) calculator.
 *
 * Computes:
 *   1. Present value of projected free cash flows
 *   2. Terminal value (Gordon Growth Model)
 *   3. Enterprise value = PV of FCFs + PV of terminal value
 *   4. Equity value = enterprise value - net debt
 *   5. Implied share price = equity value / shares outstanding
 */
@Component
public class DCFCalculator {

    private static final Logger log = LoggerFactory.getLogger(DCFCalculator.class);

    public ValuationResult compute(String jobId, CompanyInput input) {
        ValuationResult result = new ValuationResult();
        result.setJobId(jobId);
        result.setTicker(input.getTicker());

        try {
            validate(input);

            double fcf = input.getFcf();
            double wacc = input.getWacc();
            double g = input.getTerminalGrowthRate();
            int years = input.getProjectionYears();
            double netDebt = input.getNetDebt();
            double shares = input.getSharesOutstanding();

            // 1. PV of projected cash flows (assuming FCF grows at terminal rate for simplicity)
            double pvOfCashFlows = 0.0;
            double projectedFCF = fcf;
            for (int year = 1; year <= years; year++) {
                projectedFCF = projectedFCF * (1 + g);
                pvOfCashFlows += projectedFCF / Math.pow(1 + wacc, year);
            }

            // 2. Terminal value using Gordon Growth Model: TV = FCF_(n+1) / (WACC - g)
            double fcfNextYear = projectedFCF * (1 + g);
            double terminalValue = fcfNextYear / (wacc - g);

            // 3. PV of terminal value
            double pvOfTerminalValue = terminalValue / Math.pow(1 + wacc, years);

            // 4. Enterprise value
            double enterpriseValue = pvOfCashFlows + pvOfTerminalValue;

            // 5. Equity value
            double equityValue = enterpriseValue - netDebt;

            // 6. Implied share price
            double impliedSharePrice = equityValue / shares;

            result.setEnterpriseValue(round(enterpriseValue));
            result.setEquityValue(round(equityValue));
            result.setTerminalValue(round(pvOfTerminalValue));
            result.setImpliedSharePrice(round(impliedSharePrice));
            result.setSuccess(true);

            log.info("DCF computed for {} (job {}): implied price = {}", 
                     input.getTicker(), jobId, result.getImpliedSharePrice());

        } catch (Exception e) {
            log.error("DCF computation failed for {} (job {}): {}", 
                      input.getTicker(), jobId, e.getMessage());
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        }

        return result;
    }

    private void validate(CompanyInput input) {
        if (input.getTicker() == null || input.getTicker().isBlank())
            throw new IllegalArgumentException("Ticker is required");
        if (input.getFcf() == null)
            throw new IllegalArgumentException("FCF is required for " + input.getTicker());
        if (input.getWacc() == null || input.getWacc() <= 0 || input.getWacc() >= 1)
            throw new IllegalArgumentException("WACC must be between 0 and 1 for " + input.getTicker());
        if (input.getTerminalGrowthRate() == null)
            throw new IllegalArgumentException("Terminal growth rate is required for " + input.getTicker());
        if (input.getTerminalGrowthRate() >= input.getWacc())
            throw new IllegalArgumentException("Terminal growth rate must be less than WACC for " + input.getTicker());
        if (input.getProjectionYears() == null || input.getProjectionYears() <= 0)
            throw new IllegalArgumentException("Projection years must be positive for " + input.getTicker());
        if (input.getNetDebt() == null)
            throw new IllegalArgumentException("Net debt is required for " + input.getTicker());
        if (input.getSharesOutstanding() == null || input.getSharesOutstanding() <= 0)
            throw new IllegalArgumentException("Shares outstanding must be positive for " + input.getTicker());
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
