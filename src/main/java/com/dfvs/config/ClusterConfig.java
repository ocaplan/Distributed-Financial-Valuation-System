package com.dfvs.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses cluster topology from configuration.
 *
 * Format: "node-1=localhost:8081,node-2=localhost:8082,node-3=localhost:8083"
 * Each node's numeric suffix is its priority for Bully election (higher wins).
 */
@Component
public class ClusterConfig {

    private static final Logger log = LoggerFactory.getLogger(ClusterConfig.class);

    @Value("${dfvs.node-id}")
    private String selfNodeId;

    @Value("${dfvs.cluster.nodes:}")
    private String clusterSpec;

    private final Map<String, NodeAddress> peersById = new LinkedHashMap<>();
    private int selfPriority;

    @PostConstruct
    public void init() {
        if (clusterSpec == null || clusterSpec.isBlank()) {
            log.warn("No cluster.nodes configured; running as singleton node {}", selfNodeId);
            peersById.put(selfNodeId, new NodeAddress(selfNodeId, "localhost", parsePort(selfNodeId, 8080)));
        } else {
            for (String entry : clusterSpec.split(",")) {
                String[] parts = entry.trim().split("=", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException(
                        "Invalid cluster node spec '" + entry + "'. Expected nodeId=host:port");
                }
                String id = parts[0].trim();
                String[] hostPort = parts[1].trim().split(":", 2);
                if (hostPort.length != 2) {
                    throw new IllegalArgumentException(
                        "Invalid host:port in '" + entry + "'");
                }
                peersById.put(id, new NodeAddress(id, hostPort[0], Integer.parseInt(hostPort[1])));
            }
        }

        if (!peersById.containsKey(selfNodeId)) {
            throw new IllegalArgumentException(
                "Self node id '" + selfNodeId + "' not present in cluster.nodes: " + peersById.keySet());
        }

        selfPriority = priorityOf(selfNodeId);
        log.info("Cluster topology: self={} (priority={}), peers={}",
                 selfNodeId, selfPriority, peersById.keySet());
    }

    /** Extract numeric priority from a node id like "node-3" -> 3. */
    public static int priorityOf(String nodeId) {
        int dash = nodeId.lastIndexOf('-');
        if (dash < 0 || dash == nodeId.length() - 1) {
            throw new IllegalArgumentException(
                "Node id must end with -N (e.g. node-1). Got: " + nodeId);
        }
        try {
            return Integer.parseInt(nodeId.substring(dash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Node id suffix must be numeric: " + nodeId);
        }
    }

    private int parsePort(String nodeId, int defaultPort) {
        return defaultPort;
    }

    public String getSelfNodeId() {
        return selfNodeId;
    }

    public int getSelfPriority() {
        return selfPriority;
    }

    public NodeAddress getSelf() {
        return peersById.get(selfNodeId);
    }

    public Collection<NodeAddress> getAllNodes() {
        return Collections.unmodifiableCollection(peersById.values());
    }

    /** Nodes other than self. */
    public List<NodeAddress> getPeers() {
        return peersById.values().stream()
            .filter(n -> !n.id().equals(selfNodeId))
            .collect(Collectors.toList());
    }

    /** Nodes with priority strictly higher than self. */
    public List<NodeAddress> getHigherPeers() {
        return peersById.values().stream()
            .filter(n -> priorityOf(n.id()) > selfPriority)
            .collect(Collectors.toList());
    }

    public NodeAddress getNode(String nodeId) {
        return peersById.get(nodeId);
    }

    public record NodeAddress(String id, String host, int port) {
        public String baseUrl() {
            return "http://" + host + ":" + port;
        }
        public String hostPort() {
            return host + ":" + port;
        }
    }
}
