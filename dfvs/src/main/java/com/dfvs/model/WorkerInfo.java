package com.dfvs.model;

import java.time.Instant;

/**
 * Tracks the health status of a worker node.
 */
public class WorkerInfo {

    public enum WorkerHealth {
        ALIVE, UNRESPONSIVE, DEAD
    }

    private final String workerId;
    private final String address; // host:port
    private WorkerHealth health;
    private int consecutiveMisses;
    private Instant lastHeartbeat;
    private int currentEpoch;

    public WorkerInfo(String workerId, String address) {
        this.workerId = workerId;
        this.address = address;
        this.health = WorkerHealth.ALIVE;
        this.consecutiveMisses = 0;
        this.lastHeartbeat = Instant.now();
    }

    public void recordHeartbeat(int epoch) {
        this.consecutiveMisses = 0;
        this.health = WorkerHealth.ALIVE;
        this.lastHeartbeat = Instant.now();
        this.currentEpoch = epoch;
    }

    public void recordMiss(int deadThreshold) {
        this.consecutiveMisses++;
        if (consecutiveMisses >= deadThreshold) {
            this.health = WorkerHealth.DEAD;
        } else {
            this.health = WorkerHealth.UNRESPONSIVE;
        }
    }

    // --- Getters ---

    public String getWorkerId() { return workerId; }
    public String getAddress() { return address; }
    public WorkerHealth getHealth() { return health; }
    public int getConsecutiveMisses() { return consecutiveMisses; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public int getCurrentEpoch() { return currentEpoch; }
    public void setHealth(WorkerHealth health) { this.health = health; }
}
