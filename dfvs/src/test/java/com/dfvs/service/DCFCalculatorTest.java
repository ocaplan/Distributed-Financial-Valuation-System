package com.dfvs.service;

import com.dfvs.model.CompanyInput;
import com.dfvs.model.ValuationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DCFCalculatorTest {

    private final DCFCalculator calc = new DCFCalculator();

    private CompanyInput baseInput() {
        CompanyInput in = new CompanyInput("ACME");
        in.setFcf(100_000.0);          // $100M FCF (in millions)
        in.setWacc(0.10);              // 10%
        in.setTerminalGrowthRate(0.025); // 2.5%
        in.setProjectionYears(5);
        in.setNetDebt(50_000.0);
        in.setSharesOutstanding(10_000.0);
        return in;
    }

    @Test
    void computeProducesPositiveValuationsForHealthyInputs() {
        ValuationResult r = calc.compute("job1", baseInput());

        assertTrue(r.isSuccess(), "expected success but got error: " + r.getErrorMessage());
        assertTrue(r.getEnterpriseValue() > 0);
        assertTrue(r.getEquityValue() > 0);
        assertTrue(r.getImpliedSharePrice() > 0);
        assertEquals("ACME", r.getTicker());
        assertEquals("job1", r.getJobId());
    }

    @Test
    void equityValueEqualsEnterpriseValueMinusNetDebt() {
        ValuationResult r = calc.compute("job1", baseInput());
        // Allow small floating-point error from the rounding inside DCFCalculator.
        assertEquals(r.getEnterpriseValue() - 50_000.0, r.getEquityValue(), 0.05);
    }

    @Test
    void impliedSharePriceEqualsEquityValueOverShares() {
        ValuationResult r = calc.compute("job1", baseInput());
        assertEquals(r.getEquityValue() / 10_000.0, r.getImpliedSharePrice(), 0.05);
    }

    @Test
    void higherWaccProducesLowerValuation() {
        ValuationResult low = calc.compute("job1", baseInput());
        CompanyInput in = baseInput();
        in.setWacc(0.15);
        ValuationResult high = calc.compute("job1", in);

        assertTrue(low.getEnterpriseValue() > high.getEnterpriseValue(),
            "expected higher WACC to yield lower EV");
    }

    @Test
    void higherTerminalGrowthProducesHigherValuation() {
        ValuationResult base = calc.compute("job1", baseInput());
        CompanyInput in = baseInput();
        in.setTerminalGrowthRate(0.05);
        ValuationResult faster = calc.compute("job1", in);

        assertTrue(faster.getEnterpriseValue() > base.getEnterpriseValue(),
            "expected higher terminal growth to yield higher EV");
    }

    @Test
    void rejectsMissingFcf() {
        CompanyInput in = baseInput();
        in.setFcf(null);
        ValuationResult r = calc.compute("job1", in);

        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().toLowerCase().contains("fcf"));
    }

    @Test
    void rejectsWaccOutOfRange() {
        for (double bad : new double[]{0.0, -0.05, 1.0, 1.5}) {
            CompanyInput in = baseInput();
            in.setWacc(bad);
            ValuationResult r = calc.compute("job1", in);
            assertFalse(r.isSuccess(), "WACC=" + bad + " should be rejected");
        }
    }

    @Test
    void rejectsGrowthRateGreaterOrEqualToWacc() {
        // Required by the Gordon Growth Model: g < WACC, else terminal value blows up.
        CompanyInput in = baseInput();
        in.setWacc(0.05);
        in.setTerminalGrowthRate(0.06);
        ValuationResult r = calc.compute("job1", in);
        assertFalse(r.isSuccess());
    }

    @Test
    void rejectsZeroShares() {
        CompanyInput in = baseInput();
        in.setSharesOutstanding(0.0);
        ValuationResult r = calc.compute("job1", in);
        assertFalse(r.isSuccess());
    }
}
