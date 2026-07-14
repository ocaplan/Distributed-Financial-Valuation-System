package com.dfvs.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real multi-JVM integration test. Spawns three {@code java -jar} processes
 * and verifies the full set of distributed-systems behaviors the project
 * spec asks for:
 *
 *   1. Bully election produces exactly one leader, agreed by all peers.
 *   2. Jobs complete with work distributed across separate JVM processes.
 *   3. Killing a worker mid-job triggers heartbeat-based failure detection
 *      and the leader reassigns the worker's in-flight tasks.
 *   4. Killing the leader mid-job triggers re-election AND the new leader
 *      recovers incomplete tasks from the shared task log.
 *   5. The newly-elected leader accepts and completes new jobs.
 *
 * Skipped unless run with {@code -Ddfvs.integration=true}, because it needs
 * the packaged JAR and spawns real OS processes:
 *
 *   mvn package -DskipTests
 *   mvn test -Ddfvs.integration=true -Dtest=ClusterFailoverIT
 */
@EnabledIfSystemProperty(named = "dfvs.integration", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterFailoverIT {

    private static final int[] PORTS = {18091, 18092, 18093};
    private static final String CLUSTER =
        "node-1=localhost:18091,node-2=localhost:18092,node-3=localhost:18093";
    private static final Path JAR = Paths.get("target/distributed-financial-valuation-system-1.0.0.jar");
    private static final Path DATA_DIR = Paths.get("target/it-data");
    private static final Path LOG_DIR = Paths.get("target/it-logs");

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Process> nodes = new LinkedHashMap<>();

    @BeforeAll
    static void startCluster() throws Exception {
        assertTrue(Files.exists(JAR),
            "JAR not built. Run `mvn package -DskipTests` first.");
        cleanDir(DATA_DIR);
        cleanDir(LOG_DIR);

        for (int i = 0; i < PORTS.length; i++) {
            String id = "node-" + (i + 1);
            int port = PORTS[i];
            Process p = new ProcessBuilder(
                "java",
                "-jar", JAR.toAbsolutePath().toString(),
                "--server.port=" + port,
                "--dfvs.node-id=" + id,
                "--dfvs.cluster.nodes=" + CLUSTER,
                "--spring.datasource.url=jdbc:h2:file:" + DATA_DIR.toAbsolutePath() + "/dfvs;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
                // Slow tasks down so we can reliably kill a process while work is in flight.
                "--dfvs.worker.simulated-work-min-ms=800",
                "--dfvs.worker.simulated-work-max-ms=1500",
                // Speed up heartbeats so failure detection happens within the test budget.
                "--dfvs.heartbeat.interval-ms=1500",
                "--dfvs.election.leader-check-interval-ms=1500"
            )
                .redirectOutput(LOG_DIR.resolve(id + ".log").toFile())
                .redirectErrorStream(true)
                .start();
            nodes.put(id, p);
        }

        waitForCluster(45);
    }

    @AfterAll
    static void stopCluster() {
        nodes.values().forEach(Process::destroy);
        for (Process p : nodes.values()) {
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @Order(1)
    void exactlyOneLeaderElected() throws Exception {
        List<String> leaders = new ArrayList<>();
        for (int port : PORTS) {
            JsonNode st = fetchStatus(port);
            if (st != null && st.get("isLeader").asBoolean()) {
                leaders.add(st.get("nodeId").asText());
            }
        }
        assertEquals(1, leaders.size(), "expected exactly one leader, found: " + leaders);
        assertEquals("node-3", leaders.get(0),
            "Bully should pick the highest-priority node");
    }

    @Test
    @Order(2)
    void allNodesAgreeOnTheSameLeader() throws Exception {
        Set<String> leaderViews = new HashSet<>();
        for (int port : PORTS) {
            JsonNode st = fetchStatus(port);
            assertNotNull(st);
            leaderViews.add(st.get("leaderId").asText());
        }
        assertEquals(1, leaderViews.size(), "nodes disagree on leader: " + leaderViews);
    }

    @Test
    @Order(3)
    void jobCompletesAcrossSeparateJvmProcesses() throws Exception {
        int leaderPort = findLeaderPort();
        String jobId = submitJob(leaderPort,
            "{\"companies\":[{\"ticker\":\"AAPL\"},{\"ticker\":\"MSFT\"},{\"ticker\":\"GOOGL\"}]}");

        JsonNode result = pollUntilDone(leaderPort, jobId, 30_000);
        assertCompletedWithCount(result, 3);
    }

    @Test
    @Order(4)
    void workerCrashDuringJob_triggersHeartbeatReassignment() throws Exception {
        int leaderPort = findLeaderPort();
        String leaderId = fetchStatus(leaderPort).get("nodeId").asText();

        // Submit enough tasks that work is in flight when we kill the worker.
        String jobId = submitJob(leaderPort,
            "{\"companies\":[" +
                "{\"ticker\":\"AAPL\"},{\"ticker\":\"MSFT\"},{\"ticker\":\"GOOGL\"}," +
                "{\"ticker\":\"AMZN\"},{\"ticker\":\"TSLA\"},{\"ticker\":\"META\"}," +
                "{\"ticker\":\"NVDA\"},{\"ticker\":\"JPM\"}" +
            "]}");

        // Wait long enough for the leader to dispatch tasks to multiple workers.
        Thread.sleep(700);

        // Kill any worker that isn't the leader.
        String victim = nodes.keySet().stream()
            .filter(id -> !id.equals(leaderId) && nodes.get(id).isAlive())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no non-leader worker to kill"));

        nodes.get(victim).destroyForcibly();
        nodes.get(victim).waitFor();

        // The leader needs to: detect the death (heartbeat 2x miss = ~3s),
        // reassign all of victim's in-flight tasks, and let surviving workers
        // complete them. With 800-1500ms tasks and 4 surviving worker threads,
        // 25 s is a comfortable budget.
        JsonNode result = pollUntilDone(leaderPort, jobId, 30_000);
        assertCompletedWithCount(result, 8);
    }

    @Test
    @Order(5)
    void leaderCrashDuringJob_newLeaderRecoversFromTaskLog() throws Exception {
        int oldLeaderPort = findLeaderPort();
        String oldLeaderId = fetchStatus(oldLeaderPort).get("nodeId").asText();
        int oldEpoch = fetchStatus(oldLeaderPort).get("epoch").asInt();

        // Submit a multi-task job to the existing leader.
        String jobId = submitJob(oldLeaderPort,
            "{\"companies\":[" +
                "{\"ticker\":\"AAPL\"},{\"ticker\":\"MSFT\"},{\"ticker\":\"GOOGL\"}," +
                "{\"ticker\":\"AMZN\"},{\"ticker\":\"TSLA\"},{\"ticker\":\"META\"}" +
            "]}");

        // Wait long enough for tasks to be dispatched but not all completed.
        Thread.sleep(700);

        // SIGKILL the leader mid-job.
        nodes.get(oldLeaderId).destroyForcibly();
        nodes.get(oldLeaderId).waitFor();

        // Wait for a survivor to take over.
        long deadline = System.currentTimeMillis() + 25_000;
        String newLeaderId = null;
        int newLeaderPort = -1;
        int newEpoch = -1;
        outer:
        while (System.currentTimeMillis() < deadline) {
            for (int port : PORTS) {
                if (port == portOf(oldLeaderId)) continue;
                JsonNode st = fetchStatus(port);
                if (st != null && st.get("isLeader").asBoolean()) {
                    newLeaderId = st.get("nodeId").asText();
                    newLeaderPort = port;
                    newEpoch = st.get("epoch").asInt();
                    break outer;
                }
            }
            Thread.sleep(400);
        }

        assertNotNull(newLeaderId, "no new leader elected within 25s");
        assertNotEquals(oldLeaderId, newLeaderId, "killed leader must not still claim leadership");
        assertTrue(newEpoch > oldEpoch,
            "epoch must advance on leadership change (old=" + oldEpoch + ", new=" + newEpoch + ")");

        // The new leader's recoverAndResume() should re-queue any tasks the old
        // leader had in-flight, and the surviving workers should finish them.
        JsonNode result = pollUntilDone(newLeaderPort, jobId, 30_000);
        assertCompletedWithCount(result, 6);
    }

    @Test
    @Order(6)
    void newLeaderAcceptsAndCompletesFreshJobs() throws Exception {
        int leaderPort = findLeaderPort();
        String jobId = submitJob(leaderPort, "{\"companies\":[{\"ticker\":\"NVDA\"},{\"ticker\":\"JPM\"}]}");
        JsonNode result = pollUntilDone(leaderPort, jobId, 20_000);
        assertCompletedWithCount(result, 2);
    }

    // ---------- helpers ----------

    private static void assertCompletedWithCount(JsonNode result, int expected) {
        String status = result.get("status").asText();
        assertTrue(List.of("COMPLETED", "PARTIALLY_COMPLETED").contains(status),
            "expected terminal status but got " + status + ": " + result);
        assertEquals(expected, result.get("completedCompanies").asInt(),
            "missing results: " + result);
    }

    private static void waitForCluster(int maxSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            int alive = 0;
            int leaderCount = 0;
            Set<String> leaderViews = new HashSet<>();
            for (int port : PORTS) {
                JsonNode st = fetchStatus(port);
                if (st != null) {
                    alive++;
                    leaderViews.add(st.get("leaderId").asText());
                    if (st.get("isLeader").asBoolean()) leaderCount++;
                }
            }
            if (alive == PORTS.length && leaderCount == 1 && leaderViews.size() == 1) return;
            Thread.sleep(500);
        }
        fail("cluster did not reach a stable single-leader state within " + maxSeconds + "s");
    }

    private static JsonNode fetchStatus(int port) {
        try {
            HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/status"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            return JSON.readTree(r.body());
        } catch (Exception e) {
            return null;
        }
    }

    private static int findLeaderPort() {
        for (int port : PORTS) {
            JsonNode st = fetchStatus(port);
            if (st != null && st.get("isLeader").asBoolean()) return port;
        }
        throw new AssertionError("no leader currently elected");
    }

    private static int portOf(String nodeId) {
        return PORTS[Integer.parseInt(nodeId.substring(nodeId.lastIndexOf('-') + 1)) - 1];
    }

    private static String submitJob(int port, String body) throws Exception {
        HttpResponse<String> r = HTTP.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/valuations"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r.statusCode(), "submit response: " + r.body());
        return JSON.readTree(r.body()).get("jobId").asText();
    }

    private static JsonNode pollUntilDone(int port, String jobId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> r;
            try {
                r = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/valuations/" + jobId))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                // Maybe the leader changed mid-poll. Re-resolve.
                port = findLeaderPort();
                Thread.sleep(250);
                continue;
            }
            if (r.statusCode() == 200) {
                last = JSON.readTree(r.body());
                String status = last.get("status").asText();
                if ("COMPLETED".equals(status) || "PARTIALLY_COMPLETED".equals(status) || "FAILED".equals(status)) {
                    return last;
                }
            } else if (r.statusCode() == 503) {
                // Leader changed. Find the new one and keep polling.
                try { port = findLeaderPort(); } catch (AssertionError ignored) {}
            }
            Thread.sleep(300);
        }
        fail("job " + jobId + " never completed within " + timeoutMs + "ms. Last: " + last);
        return last;
    }

    private static void cleanDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.createDirectories(dir);
    }
}
