package com.dfvs.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock External Financial Data API.
 *
 * Simulates a third-party financial data provider (like Bloomberg, Refinitiv,
 * or Alpha Vantage). In production, the FinancialDataFetcher would call a real
 * external service instead of this endpoint.
 *
 * This exists as a separate REST controller to match the C4 Container Diagram,
 * which shows the Financial Data API as an external system that the Leader Node
 * communicates with over HTTP.
 *
 * Endpoint: GET /external/financials/{ticker}
 *
 * Supports simulating:
 *   - Normal responses with realistic financial data
 *   - Rate limiting (HTTP 429) on every Nth request
 *   - Random API failures (HTTP 503) to test retry logic
 */
@RestController
@RequestMapping("/external/financials")
public class ExternalFinancialDataController {

    private static final Logger log = LoggerFactory.getLogger(ExternalFinancialDataController.class);

    private final AtomicInteger requestCount = new AtomicInteger(0);

    // Simulate rate limiting every N requests (0 = disabled)
    private static final int RATE_LIMIT_EVERY_N = 0;

    // Probability of a random failure (0.0 to 1.0, 0 = disabled)
    private static final double FAILURE_PROBABILITY = 0.0;

    /**
     * Mock financial data: realistic numbers derived from public filings.
     * Each entry contains the raw financial statement data that the
     * FinancialDataFetcher will extract DCF inputs from.
     */
    private static final Map<String, Map<String, Object>> FINANCIAL_DATA = new LinkedHashMap<>();

    static {
        // Apple Inc. (AAPL) - based on approximate FY2024 figures (in millions USD)
        FINANCIAL_DATA.put("AAPL", Map.ofEntries(
            Map.entry("companyName", "Apple Inc."),
            Map.entry("ticker", "AAPL"),
            Map.entry("revenue", 391035.0),
            Map.entry("costOfRevenue", 214137.0),
            Map.entry("operatingExpenses", 55013.0),
            Map.entry("depreciationAndAmortization", 11519.0),
            Map.entry("capitalExpenditures", 10959.0),
            Map.entry("incomeTaxExpense", 29749.0),
            Map.entry("incomeBeforeTax", 123485.0),
            Map.entry("operatingIncome", 121885.0),
            Map.entry("totalDebt", 104590.0),
            Map.entry("cashAndEquivalents", 61555.0),
            Map.entry("sharesOutstanding", 15460.0),
            Map.entry("beta", 1.24),
            Map.entry("marketCap", 3420000.0),
            Map.entry("totalEquity", 56950.0),
            Map.entry("interestExpense", 3468.0),
            Map.entry("fiscalYear", "2024"),
            Map.entry("currency", "USD")
        ));

        // Microsoft Corp. (MSFT)
        FINANCIAL_DATA.put("MSFT", Map.ofEntries(
            Map.entry("companyName", "Microsoft Corporation"),
            Map.entry("ticker", "MSFT"),
            Map.entry("revenue", 245122.0),
            Map.entry("costOfRevenue", 74073.0),
            Map.entry("operatingExpenses", 61575.0),
            Map.entry("depreciationAndAmortization", 22287.0),
            Map.entry("capitalExpenditures", 44477.0),
            Map.entry("incomeTaxExpense", 16950.0),
            Map.entry("incomeBeforeTax", 109_433.0),
            Map.entry("operatingIncome", 109_433.0),
            Map.entry("totalDebt", 42688.0),
            Map.entry("cashAndEquivalents", 75530.0),
            Map.entry("sharesOutstanding", 7430.0),
            Map.entry("beta", 0.89),
            Map.entry("marketCap", 3150000.0),
            Map.entry("totalEquity", 268477.0),
            Map.entry("interestExpense", 2495.0),
            Map.entry("fiscalYear", "2024"),
            Map.entry("currency", "USD")
        ));

        // Alphabet Inc. (GOOGL)
        FINANCIAL_DATA.put("GOOGL", Map.ofEntries(
            Map.entry("companyName", "Alphabet Inc."),
            Map.entry("ticker", "GOOGL"),
            Map.entry("revenue", 350018.0),
            Map.entry("costOfRevenue", 148019.0),
            Map.entry("operatingExpenses", 81290.0),
            Map.entry("depreciationAndAmortization", 14817.0),
            Map.entry("capitalExpenditures", 52533.0),
            Map.entry("incomeTaxExpense", 16891.0),
            Map.entry("incomeBeforeTax", 106801.0),
            Map.entry("operatingIncome", 120709.0),
            Map.entry("totalDebt", 12297.0),
            Map.entry("cashAndEquivalents", 95741.0),
            Map.entry("sharesOutstanding", 12220.0),
            Map.entry("beta", 1.06),
            Map.entry("marketCap", 2350000.0),
            Map.entry("totalEquity", 325082.0),
            Map.entry("interestExpense", 399.0),
            Map.entry("fiscalYear", "2024"),
            Map.entry("currency", "USD")
        ));

        // Amazon.com Inc. (AMZN)
        FINANCIAL_DATA.put("AMZN", Map.ofEntries(
            Map.entry("companyName", "Amazon.com Inc."),
            Map.entry("ticker", "AMZN"),
            Map.entry("revenue", 637997.0),
            Map.entry("costOfRevenue", 358275.0),
            Map.entry("operatingExpenses", 225310.0),
            Map.entry("depreciationAndAmortization", 59062.0),
            Map.entry("capitalExpenditures", 83017.0),
            Map.entry("incomeTaxExpense", 10839.0),
            Map.entry("incomeBeforeTax", 59319.0),
            Map.entry("operatingIncome", 68594.0),
            Map.entry("totalDebt", 58158.0),
            Map.entry("cashAndEquivalents", 78747.0),
            Map.entry("sharesOutstanding", 10550.0),
            Map.entry("beta", 1.16),
            Map.entry("marketCap", 2280000.0),
            Map.entry("totalEquity", 285970.0),
            Map.entry("interestExpense", 3377.0),
            Map.entry("fiscalYear", "2024"),
            Map.entry("currency", "USD")
        ));

        // Tesla Inc. (TSLA)
        FINANCIAL_DATA.put("TSLA", Map.ofEntries(
            Map.entry("companyName", "Tesla Inc."),
            Map.entry("ticker", "TSLA"),
            Map.entry("revenue", 97690.0),
            Map.entry("costOfRevenue", 79268.0),
            Map.entry("operatingExpenses", 10555.0),
            Map.entry("depreciationAndAmortization", 5200.0),
            Map.entry("capitalExpenditures", 11339.0),
            Map.entry("incomeTaxExpense", 2373.0),
            Map.entry("incomeBeforeTax", 9974.0),
            Map.entry("operatingIncome", 7867.0),
            Map.entry("totalDebt", 5739.0),
            Map.entry("cashAndEquivalents", 36563.0),
            Map.entry("sharesOutstanding", 3210.0),
            Map.entry("beta", 2.31),
            Map.entry("marketCap", 1100000.0),
            Map.entry("totalEquity", 72941.0),
            Map.entry("interestExpense", 536.0),
            Map.entry("fiscalYear", "2024"),
            Map.entry("currency", "USD")
        ));

        // Meta Platforms Inc. (META)
        FINANCIAL_DATA.put("META", Map.ofEntries(
            Map.entry("companyName", "Meta Platforms Inc."),
            Map.entry("ticker", "META"),
            Map.entry("revenue", 164710.0),
            Map.entry("costOfRevenue", 27727.0),
            Map.entry("operatingExpenses", 48997.0),
            Map.entry("depreciationAndAmortization", 11178.0),
            Map.entry("capitalExpenditures", 37258.0),
            Map.entry("incomeTaxExpense", 12538.0),
            Map.entry("incomeBeforeTax", 76697.0),
            Map.entry("operatingIncome", 87986.0),
            Map.entry("totalDebt", 28826.0),
            Map.entry("cashAndEquivalents", 58076.0),
            Map.entry("sharesOutstanding", 2530.0),
            Map.entry("beta", 1.25),
            Map.entry("marketCap", 1580000.0),
            Map.entry("totalEquity", 164525.0),
            Map.entry("interestExpense", 856.0),
            Map.entry("fiscalYear", "2024"),
            Map.entry("currency", "USD")
        ));

        // NVIDIA Corp. (NVDA)
        FINANCIAL_DATA.put("NVDA", Map.ofEntries(
            Map.entry("companyName", "NVIDIA Corporation"),
            Map.entry("ticker", "NVDA"),
            Map.entry("revenue", 130497.0),
            Map.entry("costOfRevenue", 29833.0),
            Map.entry("operatingExpenses", 17543.0),
            Map.entry("depreciationAndAmortization", 1957.0),
            Map.entry("capitalExpenditures", 3234.0),
            Map.entry("incomeTaxExpense", 12979.0),
            Map.entry("incomeBeforeTax", 83158.0),
            Map.entry("operatingIncome", 83121.0),
            Map.entry("totalDebt", 8462.0),
            Map.entry("cashAndEquivalents", 43170.0),
            Map.entry("sharesOutstanding", 24500.0),
            Map.entry("beta", 1.67),
            Map.entry("marketCap", 3400000.0),
            Map.entry("totalEquity", 65899.0),
            Map.entry("interestExpense", 246.0),
            Map.entry("fiscalYear", "2025"),
            Map.entry("currency", "USD")
        ));

        // JPMorgan Chase (JPM)
        FINANCIAL_DATA.put("JPM", Map.ofEntries(
            Map.entry("companyName", "JPMorgan Chase & Co."),
            Map.entry("ticker", "JPM"),
            Map.entry("revenue", 177600.0),
            Map.entry("costOfRevenue", 0.0),  // Banks use different structure
            Map.entry("operatingExpenses", 95912.0),
            Map.entry("depreciationAndAmortization", 6400.0),
            Map.entry("capitalExpenditures", 5800.0),
            Map.entry("incomeTaxExpense", 13920.0),
            Map.entry("incomeBeforeTax", 71764.0),
            Map.entry("operatingIncome", 71764.0),
            Map.entry("totalDebt", 390000.0),
            Map.entry("cashAndEquivalents", 34500.0),
            Map.entry("sharesOutstanding", 2870.0),
            Map.entry("beta", 1.12),
            Map.entry("marketCap", 700000.0),
            Map.entry("totalEquity", 345000.0),
            Map.entry("interestExpense", 89000.0),
            Map.entry("fiscalYear", "2024"),
            Map.entry("currency", "USD")
        ));
    }

    /**
     * GET /external/financials/{ticker}
     *
     * Returns raw financial statement data for a given ticker.
     * The FinancialDataFetcher is responsible for extracting and deriving
     * DCF inputs (FCF, WACC, etc.) from this raw data.
     */
    @GetMapping("/{ticker}")
    public ResponseEntity<?> getFinancials(@PathVariable String ticker) {
        int count = requestCount.incrementAndGet();
        ticker = ticker.toUpperCase();

        log.info("[External API] Request #{} for ticker {}", count, ticker);

        // Simulate rate limiting
        if (RATE_LIMIT_EVERY_N > 0 && count % RATE_LIMIT_EVERY_N == 0) {
            log.warn("[External API] Rate limited on request #{}", count);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "2")
                .body(Map.of("error", "Rate limit exceeded. Try again later."));
        }

        // Simulate random failures
        if (FAILURE_PROBABILITY > 0 && ThreadLocalRandom.current().nextDouble() < FAILURE_PROBABILITY) {
            log.warn("[External API] Simulated failure on request #{}", count);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Service temporarily unavailable"));
        }

        // Simulate network latency (200-800ms)
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(200, 800));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Look up financial data
        Map<String, Object> data = FINANCIAL_DATA.get(ticker);
        if (data == null) {
            log.warn("[External API] Ticker {} not found", ticker);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Ticker not found: " + ticker));
        }

        log.info("[External API] Returning financial data for {}", ticker);
        return ResponseEntity.ok(data);
    }

    /**
     * GET /external/financials
     *
     * Returns list of available tickers.
     */
    @GetMapping
    public ResponseEntity<?> listTickers() {
        return ResponseEntity.ok(Map.of(
            "availableTickers", FINANCIAL_DATA.keySet(),
            "count", FINANCIAL_DATA.size()
        ));
    }
}
