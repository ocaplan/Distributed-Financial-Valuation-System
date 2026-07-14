package com.dfvs.model;

import java.util.List;

/**
 * Top-level request submitted by analysts.
 * Contains a list of companies to value.
 * This is a DTO (not persisted directly — the job record is persisted instead).
 */
public class ValuationRequest {

    private List<CompanyInput> companies;

    public ValuationRequest() {}

    public ValuationRequest(List<CompanyInput> companies) {
        this.companies = companies;
    }

    public List<CompanyInput> getCompanies() { return companies; }
    public void setCompanies(List<CompanyInput> companies) { this.companies = companies; }
}
