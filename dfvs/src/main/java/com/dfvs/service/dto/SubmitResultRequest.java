package com.dfvs.service.dto;

import com.dfvs.model.ValuationResult;

/** Worker -> Leader: completed DCF computation for one company. */
public record SubmitResultRequest(String workerId, String jobId, ValuationResult result) {}
