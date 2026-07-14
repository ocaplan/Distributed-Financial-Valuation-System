package com.dfvs.repository;

import com.dfvs.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JobRepository extends JpaRepository<JobStatus, String> {

    /**
     * Atomically increments completedCompanies and returns the new total via a
     * SQL UPDATE. This prevents the lost-update race that read-modify-write
     * suffers when multiple worker results land for the same job concurrently.
     * Caller should follow with {@link #findById(Object)} to decide whether
     * the job has reached terminal status.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE JobStatus j SET j.completedCompanies = j.completedCompanies + 1 WHERE j.jobId = :jobId")
    int incrementCompletedCount(@Param("jobId") String jobId);
}
