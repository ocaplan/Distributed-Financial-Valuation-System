package com.dfvs.repository;

import com.dfvs.model.TaskEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskEntryRepository extends JpaRepository<TaskEntry, Long> {
    List<TaskEntry> findByJobId(String jobId);
    List<TaskEntry> findByJobIdAndStatus(String jobId, TaskEntry.TaskStatus status);
    List<TaskEntry> findByStatusIn(List<TaskEntry.TaskStatus> statuses);
    List<TaskEntry> findByWorkerId(String workerId);
    List<TaskEntry> findByWorkerIdAndStatus(String workerId, TaskEntry.TaskStatus status);
}
