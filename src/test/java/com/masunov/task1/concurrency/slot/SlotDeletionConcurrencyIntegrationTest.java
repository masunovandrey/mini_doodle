package com.masunov.task1.concurrency.slot;

import com.masunov.task1.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlotDeletionConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsNotFoundForOneOfTwoConcurrentDeletesAndLeavesSlotAbsent() throws Exception {
        String calendarId = createCalendar();
        String slotId = createSlot(calendarId);
        String initialEtag = etag(calendarId, slotId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<Integer>> responses = List.of(
                    deleteSlot(executor, ready, start, calendarId, slotId, initialEtag),
                    deleteSlot(executor, ready, start, calendarId, slotId, initialEtag)
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both delete requests must become ready");
            start.countDown();

            List<Integer> statuses = List.of(
                    responses.getFirst().get(20, TimeUnit.SECONDS),
                    responses.get(1).get(20, TimeUnit.SECONDS)
            );
            assertEquals(1, statuses.stream().filter(code -> code == 204).count());
            assertEquals(1, statuses.stream().filter(code -> code == 404).count());
        } finally {
            executor.shutdownNow();
        }

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isNotFound());
        Long remainingSlots = jdbcTemplate.queryForObject(
                "select count(*) from calendar_slot where id = ?", Long.class, UUID.fromString(slotId));
        assertEquals(0L, remainingSlots);
    }

    @Test
    void returnsEitherCompleteSlotOrNotFoundWhenReadRacesWithDeletion() throws Exception {
        String calendarId = createCalendar();
        String slotId = createSlot(calendarId);
        String initialEtag = etag(calendarId, slotId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> deletion = deleteSlot(executor, ready, start, calendarId, slotId, initialEtag);
            Future<MvcResult> read = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)).andReturn();
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both read/delete contenders must become ready");
            start.countDown();
            assertEquals(204, deletion.get(20, TimeUnit.SECONDS));
            MvcResult readResult = read.get(20, TimeUnit.SECONDS);
            int readStatus = readResult.getResponse().getStatus();
            assertTrue(readStatus == 200 || readStatus == 404,
                    () -> "Unexpected GET during deletion status: " + readStatus);
            if (readStatus == 200) {
                JsonNode slot = response(readResult);
                assertEquals(slotId, slot.required("id").asString());
                assertEquals("2026-12-25T09:00:00Z", slot.required("start").asString());
                assertEquals("2026-12-25T10:00:00Z", slot.required("end").asString());
                assertEquals("FREE", slot.required("state").asString());
                assertNotNull(readResult.getResponse().getHeader(HttpHeaders.ETAG),
                        "A successful slot read must include ETag");
            }
        } finally {
            executor.shutdownNow();
        }

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isNotFound());
    }

    private Future<Integer> deleteSlot(ExecutorService executor, CountDownLatch ready, CountDownLatch start,
            String calendarId, String slotId, String etag) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(delete("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                            .header(HttpHeaders.IF_MATCH, etag))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        });
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "delete-race-" + suffix + "@example.com",
                                "name", "Delete Race " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("calendarId").asString();
    }

    private String createSlot(String calendarId) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"2026-12-25T09:00:00Z\",\"end\":\"2026-12-25T10:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("id").asString();
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

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
