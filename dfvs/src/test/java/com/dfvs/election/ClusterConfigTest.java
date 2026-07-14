package com.dfvs.election;

import com.dfvs.config.ClusterConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterConfigTest {

    @Test
    void priorityOfParsesNumericSuffix() {
        assertEquals(1, ClusterConfig.priorityOf("node-1"));
        assertEquals(7, ClusterConfig.priorityOf("node-7"));
        assertEquals(42, ClusterConfig.priorityOf("worker-42"));
    }

    @Test
    void priorityOfRejectsMalformedIds() {
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.priorityOf("node"));
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.priorityOf("node-"));
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.priorityOf("node-abc"));
    }
}
