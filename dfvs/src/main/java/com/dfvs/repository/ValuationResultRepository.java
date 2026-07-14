package com.dfvs.repository;

import com.dfvs.model.ValuationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ValuationResultRepository extends JpaRepository<ValuationResult, Long> {
    List<ValuationResult> findByJobId(String jobId);
    Optional<ValuationResult> findByJobIdAndTicker(String jobId, String ticker);
    boolean existsByJobIdAndTicker(String jobId, String ticker);
}
