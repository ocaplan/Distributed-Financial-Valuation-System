package com.dfvs.controller;

import com.dfvs.election.BullyElectionService;
import com.dfvs.election.ElectionMessages.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP endpoints for the Bully election protocol.
 *
 * Both endpoints are idempotent — duplicate messages are safe to receive.
 */
@RestController
@RequestMapping("/internal")
public class ElectionController {

    private final BullyElectionService election;

    public ElectionController(BullyElectionService election) {
        this.election = election;
    }

    @PostMapping("/election")
    public ElectionResponse election(@RequestBody ElectionRequest req) {
        return election.onElectionMessage(req);
    }

    @PostMapping("/coordinator")
    public AckResponse coordinator(@RequestBody CoordinatorMessage msg) {
        return election.onCoordinatorMessage(msg);
    }

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        return ResponseEntity.ok().build();
    }
}
