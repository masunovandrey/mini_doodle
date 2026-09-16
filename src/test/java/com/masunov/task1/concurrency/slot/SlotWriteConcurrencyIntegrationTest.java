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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlotWriteConcurrencyIntegrationTest {

    private static final Interval FIRST_REPLACEMENT = new Interval("2026-12-26T11:00:00Z", "2026-12-26T12:00:00Z");
    private static final Interval SECOND_REPLACEMENT = new Interval("2026-12-26T13:00:00Z", "2026-12-26T14:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void acceptsOneOfTwoConcurrentSameSlotIntervalWritesAndRejectsTheStaleWrite() throws Exception {
        String calendarId = createCalendar();
        Slot slot = createFreeSlot(calendarId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> firstWrite = replaceInterval(executor, ready, start, calendarId, slot, FIRST_REPLACEMENT);
            Future<Integer> secondWrite = replaceInterval(executor, ready, start, calendarId, slot, SECOND_REPLACEMENT);

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both same-slot PUT contenders must become ready");
            start.countDown();
            List<Integer> statuses = List.of(
                    firstWrite.get(20, TimeUnit.SECONDS),
                    secondWrite.get(20, TimeUnit.SECONDS)
            );

            assertTrue(statuses.stream().allMatch(statusCode -> statusCode == 200 || statusCode == 409),
                    () -> "Unexpected concurrent same-slot PUT statuses: " + statuses);
            assertEquals(1L, statuses.stream().filter(statusCode -> statusCode == 200).count());
            assertEquals(1L, statuses.stream().filter(statusCode -> statusCode == 409).count());
        } finally {
            executor.shutdownNow();
        }

        JsonNode finalSlot = response(mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id()))
                .andExpect(status().isOk())
                .andReturn());
        Interval actual = new Interval(finalSlot.required("start").asText(), finalSlot.required("end").asText());
        assertTrue(actual.equals(FIRST_REPLACEMENT) || actual.equals(SECOND_REPLACEMENT),
                () -> "Final slot interval must equal one submitted replacement, but was " + actual);
        assertEquals("FREE", finalSlot.required("state").asText());
    }

    @Test
    void acceptsOneOfTwoConcurrentSameSlotStateWritesAndRejectsTheStaleWrite() throws Exception {
        String calendarId = createCalendar();
        Slot slot = createFreeSlot(calendarId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int markBusyStatus;
        int markFreeStatus;

        try {
            Future<Integer> markBusy = changeState(executor, ready, start, calendarId, slot, "BUSY");
            Future<Integer> markFree = changeState(executor, ready, start, calendarId, slot, "FREE");

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both same-slot PATCH contenders must become ready");
            start.countDown();
            markBusyStatus = markBusy.get(20, TimeUnit.SECONDS);
            markFreeStatus = markFree.get(20, TimeUnit.SECONDS);

            List<Integer> statuses = List.of(markBusyStatus, markFreeStatus);
            assertTrue(statuses.stream().allMatch(statusCode -> statusCode == 200 || statusCode == 409),
                    () -> "Unexpected concurrent same-slot PATCH statuses: " + statuses);
            assertEquals(1L, statuses.stream().filter(statusCode -> statusCode == 200).count());
            assertEquals(1L, statuses.stream().filter(statusCode -> statusCode == 409).count());
        } finally {
            executor.shutdownNow();
        }

        String expectedFinalState = markBusyStatus == 200 ? "BUSY" : "FREE";
        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id()))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals(expectedFinalState,
                        response(result).required("state").asText()));
    }

    @Test
    void incrementsTheEtagForAnAcceptedNoOpStateWriteSoAnotherContenderBecomesStale() throws Exception {
        String calendarId = createCalendar();
        Slot slot = createFreeSlot(calendarId);

        MvcResult noOpUpdate = mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                        .header(HttpHeaders.IF_MATCH, slot.etag())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("state", "FREE"))))
                .andExpect(status().isOk())
                .andReturn();
        String noOpEtag = noOpUpdate.getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(noOpEtag, "A successful PATCH must return its new ETag");
        assertNotEquals(slot.etag(), noOpEtag,
                "An accepted no-op PATCH must still advance the version and invalidate the old ETag");

        mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                        .header(HttpHeaders.IF_MATCH, slot.etag())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("state", "BUSY"))))
                .andExpect(status().isConflict());
    }

    private Future<Integer> replaceInterval(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            String calendarId,
            Slot slot,
            Interval replacement
    ) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                            .header(HttpHeaders.IF_MATCH, slot.etag())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("start", replacement.start(), "end", replacement.end()))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        });
    }

    private Future<Integer> changeState(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            String calendarId,
            Slot slot,
            String state
    ) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                            .header(HttpHeaders.IF_MATCH, slot.etag())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("state", state))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        });
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "same-slot-write-" + suffix + "@example.com", "name", "Same Slot Write " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("calendarId").asText();
    }

    private Slot createFreeSlot(String calendarId) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("start", "2026-12-26T09:00:00Z", "end", "2026-12-26T10:00:00Z"))))
                .andExpect(status().isCreated())
                .andReturn();
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        if (etag == null) {
            throw new AssertionError("Slot creation response must contain ETag");
        }
        return new Slot(response(result).required("id").asText(), etag);
    }

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Slot(String id, String etag) {
    }

    private record Interval(String start, String end) {
    }
}
