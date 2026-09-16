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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlotConflictConcurrencyIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void rejectsOneOfTwoConcurrentOverlappingSlotCreations() throws Exception {
        String calendarId = createCalendar();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> responses = List.of(
                    createSlot(executor, ready, start, calendarId, "2026-12-22T09:00:00Z", "2026-12-22T10:00:00Z"),
                    createSlot(executor, ready, start, calendarId, "2026-12-22T09:30:00Z", "2026-12-22T10:30:00Z")
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = List.of(responses.getFirst().get(20, TimeUnit.SECONDS), responses.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(statusCode -> statusCode == 201).count());
            assertEquals(1, statuses.stream().filter(statusCode -> statusCode == 409).count());
        } finally {
            executor.shutdownNow();
        }
        Long count = jdbcTemplate.queryForObject("select count(*) from calendar_slot where calendar_id = ?", Long.class, UUID.fromString(calendarId));
        assertEquals(1L, count);
    }

    @Test
    void rejectsOneOfTwoConcurrentUpdatesThatWouldOverlap() throws Exception {
        String calendarId = createCalendar();
        Slot first = createSlot(calendarId, "2026-12-23T09:00:00Z", "2026-12-23T10:00:00Z");
        Slot second = createSlot(calendarId, "2026-12-23T11:00:00Z", "2026-12-23T12:00:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> responses = List.of(
                    updateSlot(executor, ready, start, calendarId, first,
                            "2026-12-23T10:00:00Z", "2026-12-23T11:00:00Z"),
                    updateSlot(executor, ready, start, calendarId, second,
                            "2026-12-23T10:30:00Z", "2026-12-23T11:30:00Z")
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = List.of(responses.getFirst().get(20, TimeUnit.SECONDS), responses.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(code -> code == 200).count());
            assertEquals(1, statuses.stream().filter(code -> code == 409).count());
            if (statuses.getFirst() == 409) {
                assertSlotInterval(first.id(), "2026-12-23T09:00:00Z", "2026-12-23T10:00:00Z");
            } else {
                assertSlotInterval(second.id(), "2026-12-23T11:00:00Z", "2026-12-23T12:00:00Z");
            }
        } finally {
            executor.shutdownNow();
        }
        Long overlaps = jdbcTemplate.queryForObject("select count(*) from calendar_slot a join calendar_slot b on a.calendar_id=b.calendar_id and a.id<b.id and tstzrange(a.start_at,a.end_at,'[)') && tstzrange(b.start_at,b.end_at,'[)') where a.calendar_id=?", Long.class, UUID.fromString(calendarId));
        assertEquals(0L, overlaps);
    }

    @Test
    void rejectsOneOfAConcurrentCreateAndUpdateThatWouldOverlap() throws Exception {
        String calendarId = createCalendar();
        Slot existingSlot = createSlot(calendarId, "2026-12-24T09:00:00Z", "2026-12-24T10:00:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> createResponse = createSlot(executor, ready, start, calendarId,
                    "2026-12-24T10:30:00Z", "2026-12-24T11:30:00Z");
            Future<Integer> updateResponse = updateSlot(executor, ready, start, calendarId, existingSlot,
                    "2026-12-24T10:00:00Z", "2026-12-24T11:00:00Z");
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int createStatus = createResponse.get(20, TimeUnit.SECONDS);
            int updateStatus = updateResponse.get(20, TimeUnit.SECONDS);
            assertEquals(1, List.of(createStatus, updateStatus).stream()
                    .filter(code -> code == 201 || code == 200)
                    .count());
            assertEquals(1, List.of(createStatus, updateStatus).stream()
                    .filter(code -> code == 409)
                    .count());
            if (createStatus == 201) {
                assertSlotInterval(existingSlot.id(), "2026-12-24T09:00:00Z", "2026-12-24T10:00:00Z");
                assertSlotCount(calendarId, 2L);
            } else {
                assertSlotInterval(existingSlot.id(), "2026-12-24T10:00:00Z", "2026-12-24T11:00:00Z");
                assertSlotCount(calendarId, 1L);
            }
        } finally {
            executor.shutdownNow();
        }
        Long overlaps = jdbcTemplate.queryForObject("select count(*) from calendar_slot a join calendar_slot b on a.calendar_id=b.calendar_id and a.id<b.id and tstzrange(a.start_at,a.end_at,'[)') && tstzrange(b.start_at,b.end_at,'[)') where a.calendar_id=?", Long.class, UUID.fromString(calendarId));
        assertEquals(0L, overlaps);
    }

    private Future<Integer> createSlot(ExecutorService executor, CountDownLatch ready, CountDownLatch start, String calendarId, String slotStart, String slotEnd) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("start", slotStart, "end", slotEnd))))
                    .andReturn().getResponse().getStatus();
        });
    }

    private Slot createSlot(String calendarId, String slotStart, String slotEnd) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("start", slotStart, "end", slotEnd))))
                .andExpect(status().isCreated()).andReturn();
        return new Slot(objectMapper.readTree(result.getResponse().getContentAsString()).required("id").asText(),
                result.getResponse().getHeader(HttpHeaders.ETAG));
    }

    private Future<Integer> updateSlot(ExecutorService executor, CountDownLatch ready, CountDownLatch start, String calendarId, Slot slot, String slotStart, String slotEnd) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                            .header(HttpHeaders.IF_MATCH, slot.etag())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("start", slotStart, "end", slotEnd))))
                    .andReturn().getResponse().getStatus();
        });
    }

    private void assertSlotInterval(String slotId, String slotStart, String slotEnd) {
        Long matches = jdbcTemplate.queryForObject("select count(*) from calendar_slot where id = ? and start_at = ?::timestamptz and end_at = ?::timestamptz", Long.class, UUID.fromString(slotId), slotStart, slotEnd);
        assertEquals(1L, matches);
    }

    private void assertSlotCount(String calendarId, long expectedCount) {
        Long count = jdbcTemplate.queryForObject("select count(*) from calendar_slot where calendar_id = ?", Long.class,
                UUID.fromString(calendarId));
        assertEquals(expectedCount, count);
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "slot-race-" + suffix + "@example.com", "name", "Slot Race " + suffix))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.required("calendarId").asText();
    }
    private record Slot(String id, String etag) {
    }
}
