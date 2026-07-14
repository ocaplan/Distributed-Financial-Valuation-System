package com.dfvs.election;

import com.dfvs.config.ClusterConfig;
import com.dfvs.election.ElectionMessages.*;
import com.dfvs.service.HeartbeatService;
import com.dfvs.service.LeaderService;
import com.dfvs.service.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Protocol-level unit tests for the Bully election handlers.
 *
 * Exercises {@link BullyElectionService#onElectionMessage} and
 * {@link BullyElectionService#onCoordinatorMessage} in isolation, with mocked
 * collaborators. The async election trigger is replaced with a no-op so we can
 * make deterministic state assertions.
 */
class BullyElectionLogicTest {

    private ClusterConfig cluster;
    private LeaderService leaderService;
    private HeartbeatService heartbeatService;
    private WorkerService workerService;
    private BullyElectionService election;

    @BeforeEach
    void setUp() {
        // ClusterConfig is a non-mockable real bean (final-ish init logic);
        // build a real instance with this node as node-2 of a 3-node cluster.
        cluster = new ClusterConfig();
        ReflectionTestUtils.setField(cluster, "selfNodeId", "node-2");
        ReflectionTestUtils.setField(cluster, "clusterSpec",
            "node-1=localhost:8081,node-2=localhost:8082,node-3=localhost:8083");
        cluster.init();

        leaderService = mock(LeaderService.class);
        heartbeatService = mock(HeartbeatService.class);
        workerService = mock(WorkerService.class);

        election = new BullyElectionService(cluster, leaderService, heartbeatService, workerService);

        // Don't actually fire async elections during these tests.
        replaceField(election, "electionExecutor", noOpExecutor());
    }

    @Test
    void higherPriorityNodeOutranksLowerChallenger() {
        ElectionResponse resp = election.onElectionMessage(new ElectionRequest("node-1", 1));

        assertTrue(resp.outranks());
        assertEquals("node-2", resp.fromNodeId());
        assertEquals(2, resp.fromPriority());
    }

    @Test
    void lowerPriorityNodeDoesNotOutrankHigherChallenger() {
        ElectionResponse resp = election.onElectionMessage(new ElectionRequest("node-3", 3));

        assertFalse(resp.outranks());
        assertEquals(2, resp.fromPriority());
    }

    @Test
    void coordinatorFromHigherNodeIsAccepted() {
        AckResponse ack = election.onCoordinatorMessage(new CoordinatorMessage("node-3", 3, 5));

        assertTrue(ack.ok());
        assertEquals("node-3", election.getLeaderId());
        assertEquals(5, election.getEpoch());
        verify(workerService).registerWithLeader(any(ClusterConfig.NodeAddress.class));
    }

    @Test
    void coordinatorFromLowerNodeIsRejected() {
        AckResponse ack = election.onCoordinatorMessage(new CoordinatorMessage("node-1", 1, 5));

        assertFalse(ack.ok());
        assertNotEquals("node-1", election.getLeaderId());
        verify(workerService, never()).registerWithLeader(any());
    }

    @Test
    void staleCoordinatorIsRejected() {
        election.onCoordinatorMessage(new CoordinatorMessage("node-3", 3, 5));
        clearInvocations(workerService);

        AckResponse ack = election.onCoordinatorMessage(new CoordinatorMessage("node-3", 3, 2));

        assertFalse(ack.ok());
        verify(workerService, never()).registerWithLeader(any());
    }

    @Test
    void epochIncrementsAcrossLeadershipChanges() {
        election.onCoordinatorMessage(new CoordinatorMessage("node-3", 3, 1));
        assertEquals(1, election.getEpoch());
        election.onCoordinatorMessage(new CoordinatorMessage("node-3", 3, 4));
        assertEquals(4, election.getEpoch());
    }

    private static java.util.concurrent.ExecutorService noOpExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            public void shutdown() {}
            public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
            public void execute(Runnable r) {}
        };
    }

    private static void replaceField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
