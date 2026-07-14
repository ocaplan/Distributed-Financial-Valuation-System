package com.dfvs.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.annotation.PostConstruct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end controller tests against a real Spring context with an in-memory
 * H2. The single test node self-elects (no peers in cluster.nodes), so the
 * full submit -> distribute -> compute -> aggregate path runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
    "server.port=18099",
    "dfvs.node-id=node-9",
    "dfvs.cluster.nodes=node-9=localhost:18099",
    "spring.datasource.url=jdbc:h2:mem:valctrl_${random.uuid};DB_CLOSE_DELAY=-1",
    "dfvs.worker.simulated-work-min-ms=10",
    "dfvs.worker.simulated-work-max-ms=30",
    "dfvs.election.leader-check-interval-ms=3600000"
})
class ValuationControllerTest {

    @Autowired private WebApplicationContext ctx;
    @Autowired private ObjectMapper mapper;

    private MockMvc mvc;

    @PostConstruct
    void initMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    void rejectsEmptyCompanyList() throws Exception {
        mvc.perform(post("/valuations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companies\":[]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankTicker() throws Exception {
        mvc.perform(post("/valuations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companies\":[{\"ticker\":\"  \"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidTickerFormat() throws Exception {
        mvc.perform(post("/valuations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companies\":[{\"ticker\":\"too-long-7\"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void returns404ForUnknownJob() throws Exception {
        mvc.perform(get("/valuations/does-not-exist"))
            .andExpect(status().isNotFound());
    }

    @Test
    void submitReturns202AndCompletesEventually() throws Exception {
        waitUntilLeader();

        MvcResult submit = mvc.perform(post("/valuations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companies\":[" +
                    "{\"ticker\":\"AAPL\"}," +
                    "{\"ticker\":\"MSFT\"}," +
                    "{\"ticker\":\"GOOGL\"}" +
                    "]}"))
            .andExpect(status().isAccepted())
            .andReturn();

        JsonNode body = mapper.readTree(submit.getResponse().getContentAsString());
        String jobId = body.get("jobId").asText();
        assertThat(jobId).isNotBlank();
        assertThat(body.get("totalCompanies").asInt()).isEqualTo(3);

        // Poll for completion (computations include the 10–30 ms simulated work delay).
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode status;
        do {
            Thread.sleep(200);
            MvcResult poll = mvc.perform(get("/valuations/" + jobId)).andReturn();
            status = mapper.readTree(poll.getResponse().getContentAsString());
        } while (System.currentTimeMillis() < deadline
                 && !"COMPLETED".equals(status.get("status").asText())
                 && !"PARTIALLY_COMPLETED".equals(status.get("status").asText()));

        assertThat(status.get("status").asText()).isIn("COMPLETED", "PARTIALLY_COMPLETED");
        assertThat(status.get("completedCompanies").asInt()).isEqualTo(3);
        assertThat(status.get("results")).hasSize(3);
    }

    /** Block briefly until the single test node has self-elected. */
    private void waitUntilLeader() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult st = mvc.perform(get("/internal/status")).andReturn();
            if (st.getResponse().getStatus() == 200) {
                JsonNode j = mapper.readTree(st.getResponse().getContentAsString());
                if (j.get("isLeader").asBoolean()) return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("test node never became leader");
    }
}
