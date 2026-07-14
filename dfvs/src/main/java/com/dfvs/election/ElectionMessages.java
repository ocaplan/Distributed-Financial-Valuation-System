package com.dfvs.election;

/**
 * Wire-format messages for the Bully election protocol.
 *
 * Three message types:
 *   ELECTION    - "I am starting an election; respond if you outrank me."
 *   OK          - Response to ELECTION; sender outranks the challenger and will take over.
 *   COORDINATOR - "I won; I am the new leader as of this epoch."
 */
public final class ElectionMessages {

    private ElectionMessages() {}

    /** Body of POST /internal/election. */
    public record ElectionRequest(String fromNodeId, int fromPriority) {}

    /** Body of POST /internal/election response. */
    public record ElectionResponse(String fromNodeId, int fromPriority, boolean outranks) {}

    /** Body of POST /internal/coordinator. */
    public record CoordinatorMessage(String leaderNodeId, int leaderPriority, int epoch) {}

    /** Body of POST /internal/coordinator response. */
    public record AckResponse(boolean ok) {}
}
