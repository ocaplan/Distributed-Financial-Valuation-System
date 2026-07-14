package com.dfvs.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the leader-failover-preserves-overrides behavior. If a job is
 * submitted with custom analyst overrides and the leader dies before those
 * tasks have run, the new leader must re-dispatch with the SAME overrides —
 * not fall back to generic cached data.
 */
class TaskEntryOverridesTest {

    @Test
    void setOverridesPersistsEveryField() {
        CompanyInput in = new CompanyInput("AAPL");
        in.setFcf(120_000.0);
        in.setWacc(0.085);
        in.setTerminalGrowthRate(0.022);
        in.setProjectionYears(7);
        in.setNetDebt(45_000.0);
        in.setSharesOutstanding(15_500.0);

        TaskEntry task = new TaskEntry("job-1", "AAPL");
        task.setOverrides(in);

        CompanyInput recovered = task.toOverrideOnlyInput();

        assertEquals("AAPL", recovered.getTicker());
        assertEquals(120_000.0, recovered.getFcf());
        assertEquals(0.085, recovered.getWacc());
        assertEquals(0.022, recovered.getTerminalGrowthRate());
        assertEquals(7, recovered.getProjectionYears());
        assertEquals(45_000.0, recovered.getNetDebt());
        assertEquals(15_500.0, recovered.getSharesOutstanding());
    }

    @Test
    void partialOverridesRoundtripCleanlyWithNullsPreserved() {
        // Analyst supplied WACC but nothing else — recovery must keep WACC set
        // and leave the other fields null so the fetcher can fill them in.
        CompanyInput in = new CompanyInput("MSFT");
        in.setWacc(0.11);

        TaskEntry task = new TaskEntry("job-2", "MSFT");
        task.setOverrides(in);

        CompanyInput recovered = task.toOverrideOnlyInput();

        assertEquals(0.11, recovered.getWacc());
        assertNull(recovered.getFcf());
        assertNull(recovered.getTerminalGrowthRate());
        assertNull(recovered.getProjectionYears());
        assertNull(recovered.getNetDebt());
        assertNull(recovered.getSharesOutstanding());
    }

    @Test
    void emptyOverridesProduceAllNullsExceptTicker() {
        TaskEntry task = new TaskEntry("job-3", "GOOGL");
        CompanyInput recovered = task.toOverrideOnlyInput();

        assertEquals("GOOGL", recovered.getTicker());
        assertNull(recovered.getFcf());
        assertNull(recovered.getWacc());
    }
}
