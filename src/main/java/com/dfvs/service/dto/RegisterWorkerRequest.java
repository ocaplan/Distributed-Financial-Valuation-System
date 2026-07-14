package com.dfvs.service.dto;

/** Worker -> Leader: announce presence so the leader can heartbeat us. */
public record RegisterWorkerRequest(String workerId, String host, int port) {
    public String hostPort() {
        return host + ":" + port;
    }
}
