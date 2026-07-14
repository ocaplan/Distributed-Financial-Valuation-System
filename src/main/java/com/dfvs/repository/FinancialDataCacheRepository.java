package com.dfvs.repository;

import com.dfvs.model.CachedFinancialData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FinancialDataCacheRepository extends JpaRepository<CachedFinancialData, String> {
}
