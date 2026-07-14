package com.dfvs.service.dto;

import com.dfvs.model.CompanyInput;

/**
 * Wire-format task handed from leader to worker over HTTP.
 *
 * Flattens CompanyInput's fields so the wire payload is independent of JPA
 * entity quirks. The worker rebuilds a transient CompanyInput from this.
 */
public record TaskAssignment(
    Long taskEntryId,
    String jobId,
    String ticker,
    Double fcf,
    Double wacc,
    Double terminalGrowthRate,
    Integer projectionYears,
    Double netDebt,
    Double sharesOutstanding
) {

    public static TaskAssignment of(Long taskEntryId, String jobId, CompanyInput input) {
        return new TaskAssignment(
            taskEntryId,
            jobId,
            input.getTicker(),
            input.getFcf(),
            input.getWacc(),
            input.getTerminalGrowthRate(),
            input.getProjectionYears(),
            input.getNetDebt(),
            input.getSharesOutstanding()
        );
    }

    public CompanyInput toCompanyInput() {
        CompanyInput input = new CompanyInput(ticker);
        input.setFcf(fcf);
        input.setWacc(wacc);
        input.setTerminalGrowthRate(terminalGrowthRate);
        input.setProjectionYears(projectionYears);
        input.setNetDebt(netDebt);
        input.setSharesOutstanding(sharesOutstanding);
        return input;
    }
}
