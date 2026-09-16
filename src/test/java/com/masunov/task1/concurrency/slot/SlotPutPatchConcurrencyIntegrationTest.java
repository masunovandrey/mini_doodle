package com.masunov.task1.concurrency.slot;

import com.masunov.task1.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlotPutPatchConcurrencyIntegrationTest {

    private static final String ORIGINAL_START = "2026-12-26T09:00:00Z";
    private static final String ORIGINAL_END = "2026-12-26T10:00:00Z";
    private static final String PUT_START = "2026-12-26T11:00:00Z";
    private static final String PUT_END = "2026-12-26T12:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrentPutAndPatchResolveToOneWinnerWithoutSilentlyUndoingTheOtherField() throws Exception {
        String calendarId = createCalendar();
        String slotId = createSlot(calendarId);
        String initialEtag = etag(calendarId, slotId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> results;
        try {
            results = List.of(
                    updateInterval(executor, ready, start, calendarId, slotId, initialEtag),
                    changeState(executor, ready, start, calendarId, slotId, initialEtag)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both PUT and PATCH requests must become ready");
            start.countDown();

            List<MvcResult> responses = List.of(
                    results.getFirst().get(20, TimeUnit.SECONDS),
                    results.get(1).get(20, TimeUnit.SECONDS)
            );
            List<Integer> statuses = responses.stream()
                    .map(response -> response.getResponse().getStatus())
                    .toList();
            assertEquals(1, statuses.stream().filter(code -> code == 200).count(),
                    () -> "Exactly one of PUT/PATCH must win: " + statuses);
            assertEquals(1, statuses.stream().filter(code -> code == 409).count(),
                    () -> "Exactly one of PUT/PATCH must be stale: " + statuses);

            MvcResult winner = responses.stream()
                    .filter(response -> response.getResponse().getStatus() == 200)
                    .findFirst()
                    .orElseThrow();
            MvcResult loser = responses.stream()
                    .filter(response -> response.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();

            assertRejectedWithConflict(loser);
            assertFinalStateReflectsOnlyTheWinner(calendarId, slotId, initialEtag, winner);
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertRejectedWithConflict(MvcResult loser) throws Exception {
        JsonNode problemDetails = objectMapper.readTree(loser.getResponse().getContentAsString());
        assertEquals(409, problemDetails.required("status").asInt(),
                "The stale contender must be rejected with an RFC 9457 409 Problem Details body");
    }

    private void assertFinalStateReflectsOnlyTheWinner(String calendarId, String slotId, String initialEtag,
            MvcResult winner) throws Exception {
        String winnerEtag = winner.getResponse().getHeader(HttpHeaders.ETAG);
        boolean intervalWon = "PUT".equals(winner.getRequest().getMethod());

        MvcResult finalRead = mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode slot = objectMapper.readTree(finalRead.getResponse().getContentAsString());

        assertNotEquals(initialEtag, finalRead.getResponse().getHeader(HttpHeaders.ETAG),
                "A committed mutation must increment the slot version");
        assertEquals(winnerEtag, finalRead.getResponse().getHeader(HttpHeaders.ETAG),
                "The final ETag must match the winning mutation");

        if (intervalWon) {
            assertEquals(PUT_START, slot.required("start").asText(),
                    "Winning PUT interval start must be preserved");
            assertEquals(PUT_END, slot.required("end").asText(),
                    "Winning PUT interval end must be preserved");
            assertEquals("FREE", slot.required("state").asText(),
                    "PUT must not silently change the slot state");
        } else {
            assertEquals(ORIGINAL_START, slot.required("start").asText(),
                    "Winning PATCH must not silently change the slot interval start");
            assertEquals(ORIGINAL_END, slot.required("end").asText(),
                    "Winning PATCH must not silently change the slot interval end");
            assertEquals("BUSY", slot.required("state").asText(),
                    "Winning PATCH state must be preserved");
        }
    }

    private Future<MvcResult> updateInterval(ExecutorService executor, CountDownLatch ready, CountDownLatch start,
            String calendarId, String slotId, String etag) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                            .header(HttpHeaders.IF_MATCH, etag)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("start", PUT_START, "end", PUT_END))))
                    .andReturn();
        });
    }

    private Future<MvcResult> changeState(ExecutorService executor, CountDownLatch ready, CountDownLatch start,
            String calendarId, String slotId, String etag) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                            .header(HttpHeaders.IF_MATCH, etag)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"state\":\"BUSY\"}"))
                    .andReturn();
        });
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "put-patch-race-" + suffix + "@example.com",
                                "name", "Put Patch Race " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).required("calendarId").asText();
    }

    private String createSlot(String calendarId) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("start", ORIGINAL_START, "end", ORIGINAL_END))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).required("id").asText();
    }

    private String etag(String calendarId, String slotId) throws Exception {
        String value = mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
        if (value == null) {
            throw new AssertionError("Slot response must contain ETag");
        }
        return value;
    }
}