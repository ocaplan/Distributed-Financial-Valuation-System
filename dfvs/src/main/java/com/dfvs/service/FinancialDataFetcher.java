package com.dfvs.service;

import com.dfvs.model.CachedFinancialData;
import com.dfvs.model.CompanyInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Financial Data Fetcher.
 *
 * Retrieves financial data from the external Financial Data API and caches it
 * in the Data Store. Follows the state machine from the design:
 *
 *   CheckCache --[miss/stale]--> FetchingExternal --[apiSuccess]--> Caching --> ReturningData
 *       |                             |       ^                                    ^
 *       |                        [apiFailed]  [rateLimited]                        |
 *       |                             |       | (retry w/ backoff)                 |
 *       |                             v       |                                    |
 *       |                           Error     +------------------------------------+
 *       |                                                                          |
 *       +--[cacheHit]-------------------------------------------------------------+
 *
 * Derives DCF inputs from raw financial statement data:
 *   FCF   = Operating Income * (1 - tax rate) + D and A - CapEx
 *   WACC  = (E/V * Re) + (D/V * Rd * (1 - T)), where Re uses CAPM
 *   Net Debt = Total Debt - Cash
 *   Shares Outstanding from API response
 *
 * User-provided override values always take precedence over fetched/cached data.
 */
@Service
public class FinancialDataFetcher {

    private static final Logger log = LoggerFactory.getLogger(FinancialDataFetcher.class);

    private final DataStoreService dataStore;
    private final Environment environment;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Base URL of the (logically external) Financial Data API.
     * Empty default means "fall back to the locally-hosted mock controller",
     * which is the convenient single-process demo mode. In a production
     * deployment, point this at a real provider (Bloomberg, Refinitiv,
     * Alpha Vantage, ...) so the cluster has no in-process dependency on
     * the mock implementation.
     */
    @Value("${dfvs.external-api.base-url:}")
    private String externalApiBaseUrl;

    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    // CAPM assumptions
    private static final double RISK_FREE_RATE = 0.043;
    private static final double MARKET_PREMIUM = 0.055;
    private static final double DEFAULT_TERMINAL_GROWTH = 0.025;
    private static final int DEFAULT_PROJECTION_YEARS = 5;

    public FinancialDataFetcher(DataStoreService dataStore, Environment environment) {
        this.dataStore = dataStore;
        this.environment = environment;
    }

    /**
     * Resolves the Financial Data API URL. Prefers {@code dfvs.external-api.base-url}
     * when set; otherwise falls back to the co-located mock controller on this
     * node's HTTP port. Under {@code RANDOM_PORT} in tests, {@code local.server.port}
     * is only set after Tomcat boots, so a field-injected {@code ${server.port}}
     * would freeze at "0" — hence the lazy lookup.
     */
    private String getExternalApiUrl(String ticker) {
        if (externalApiBaseUrl != null && !externalApiBaseUrl.isBlank()) {
            String base = externalApiBaseUrl.endsWith("/")
                ? externalApiBaseUrl.substring(0, externalApiBaseUrl.length() - 1)
                : externalApiBaseUrl;
            return base + "/external/financials/" + ticker;
        }
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            port = environment.getProperty("server.port", "8080");
        }
        return "http://localhost:" + port + "/external/financials/" + ticker;
    }

    /**
     * Fetch financial data for a company. Checks cache first, falls back to external API.
     * User-provided overrides in the CompanyInput take precedence.
     */
    public CompanyInput fetchAndMerge(CompanyInput input) {
        String ticker = input.getTicker().toUpperCase();
        input.setTicker(ticker);

        // If all fields are already provided by the analyst, skip fetch entirely
        if (isComplete(input)) {
            log.info("All data provided by analyst for {}, skipping fetch", ticker);
            return input;
        }

        // STATE: CheckCache
        Optional<CachedFinancialData> cached = dataStore.getCachedData(ticker);
        if (cached.isPresent()) {
            log.info("Cache hit for {} (fresh TTL)", ticker);
            return cached.get().mergeInto(input);
        }

        // STATE: FetchingExternal (cache miss or stale)
        log.info("Cache miss/stale for {}, fetching from external API", ticker);
        CachedFinancialData fetched = fetchFromExternalAPI(ticker);

        if (fetched != null) {
            // STATE: Caching
            dataStore.cacheFinancialData(fetched);
            log.info("Cached derived financial data for {} with TTL", ticker);

            // STATE: ReturningData
            return fetched.mergeInto(input);
        }

        // STATE: Error - API failed after retries
        log.error("Failed to fetch data for {} after {} retries", ticker, MAX_RETRIES);
        return input;
    }

    /**
     * Calls the external Financial Data API with retry and exponential backoff.
     * Handles rate limiting (429) by retrying, and server errors (5xx) with backoff.
     */
    @SuppressWarnings("unchecked")
    private CachedFinancialData fetchFromExternalAPI(String ticker) {
        String url = getExternalApiUrl(ticker);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("External API call attempt {}/{} for {}", attempt, MAX_RETRIES, ticker);

                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> rawData = response.getBody();
                    return deriveFinancialInputs(ticker, rawData);
                }

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    // Rate limited - retry with backoff
                    long backoff = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
                    log.warn("Rate limited for {} (attempt {}), retrying in {}ms",
                             ticker, attempt, backoff);
                    sleep(backoff);
                    continue;
                } else if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    log.warn("Ticker {} not found in external API", ticker);
                    return null; // No point retrying
                }
                log.error("Client error fetching {}: {}", ticker, e.getStatusCode());

            } catch (HttpServerErrorException e) {
                // Server error - retry with exponential backoff
                long backoff = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
                log.warn("Server error for {} (attempt {}): {}, retrying in {}ms",
                         ticker, attempt, e.getStatusCode(), backoff);
                sleep(backoff);
                continue;

            } catch (Exception e) {
                // Network error - retry with backoff
                long backoff = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
                log.warn("Network error for {} (attempt {}): {}, retrying in {}ms",
                         ticker, attempt, e.getMessage(), backoff);
                sleep(backoff);
                continue;
            }
        }

        // All retries exhausted
        return null;
    }

    /**
     * Derives DCF inputs from raw financial statement data returned by the API.
     *
     *   FCF   = NOPAT + D&A - CapEx
     *           where NOPAT = Operating Income * (1 - effective tax rate)
     *
     *   WACC  = (E/V * Re) + (D/V * Rd * (1 - T))
     *           Re = Rf + beta * (Rm - Rf)           [CAPM]
     *           Rd = Interest Expense / Total Debt
     *           T  = effective tax rate
     *           E  = market cap, D = total debt, V = E + D
     *
     *   Net Debt = Total Debt - Cash & Equivalents
     */
    private CachedFinancialData deriveFinancialInputs(String ticker, Map<String, Object> raw) {
        double operatingIncome = getDouble(raw, "operatingIncome");
        double da = getDouble(raw, "depreciationAndAmortization");
        double capex = getDouble(raw, "capitalExpenditures");
        double incomeTaxExpense = getDouble(raw, "incomeTaxExpense");
        double incomeBeforeTax = getDouble(raw, "incomeBeforeTax");
        double totalDebt = getDouble(raw, "totalDebt");
        double cash = getDouble(raw, "cashAndEquivalents");
        double sharesOutstanding = getDouble(raw, "sharesOutstanding");
        double beta = getDouble(raw, "beta");
        double marketCap = getDouble(raw, "marketCap");
        double interestExpense = getDouble(raw, "interestExpense");

        // Effective tax rate
        double effectiveTaxRate = incomeBeforeTax > 0
            ? incomeTaxExpense / incomeBeforeTax
            : 0.21; // Default to US corporate rate

        // FCF = NOPAT + D&A - CapEx
        double nopat = operatingIncome * (1 - effectiveTaxRate);
        double fcf = nopat + da - capex;

        // WACC via CAPM
        double costOfEquity = RISK_FREE_RATE + beta * MARKET_PREMIUM;
        double costOfDebt = totalDebt > 0
            ? interestExpense / totalDebt
            : 0.05;
        double afterTaxCostOfDebt = costOfDebt * (1 - effectiveTaxRate);

        // Capital structure weights using market values
        double totalValue = marketCap + totalDebt;
        double equityWeight = totalValue > 0 ? marketCap / totalValue : 0.8;
        double debtWeight = totalValue > 0 ? totalDebt / totalValue : 0.2;

        double wacc = (equityWeight * costOfEquity) + (debtWeight * afterTaxCostOfDebt);

        // Ensure WACC is within reasonable bounds
        wacc = Math.max(0.04, Math.min(wacc, 0.20));

        // Net Debt
        double netDebt = totalDebt - cash;

        log.info("Derived DCF inputs for {}: FCF={}, WACC={}, NetDebt={}, Shares={}",
                 ticker,
                 String.format("%.0f", fcf),
                 String.format("%.4f", wacc),
                 String.format("%.0f", netDebt),
                 String.format("%.0f", sharesOutstanding));

        // Build cached entry
        CachedFinancialData cached = new CachedFinancialData();
        cached.setTicker(ticker);
        cached.setFcf(fcf);
        cached.setWacc(wacc);
        cached.setTerminalGrowthRate(DEFAULT_TERMINAL_GROWTH);
        cached.setProjectionYears(DEFAULT_PROJECTION_YEARS);
        cached.setNetDebt(netDebt);
        cached.setSharesOutstanding(sharesOutstanding);
        return cached;
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    private boolean isComplete(CompanyInput input) {
        return input.getFcf() != null
            && input.getWacc() != null
            && input.getTerminalGrowthRate() != null
            && input.getProjectionYears() != null
            && input.getNetDebt() != null
            && input.getSharesOutstanding() != null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
