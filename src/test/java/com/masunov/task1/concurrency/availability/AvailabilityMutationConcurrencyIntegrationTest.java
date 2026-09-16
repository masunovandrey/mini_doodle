package com.masunov.task1.concurrency.availability;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AvailabilityMutationConcurrencyIntegrationTest {

    private static final String FRAME_START = "2026-12-28T08:00:00Z";
    private static final String FRAME_END = "2026-12-28T15:00:00Z";
    private static final Interval ORIGINAL = new Interval("2026-12-28T09:00:00Z", "2026-12-28T10:00:00Z");
    private static final Interval REPLACEMENT = new Interval("2026-12-28T11:00:00Z", "2026-12-28T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsCommittedAvailabilityDuringSlotCreation() throws Exception {
        String calendarId = createCalendar();

        RaceResult race = readAvailabilityDuring(calendarId, () -> createSlotStatus(calendarId, ORIGINAL));

        assertEquals(201, race.mutationStatus());
        assertValidAvailability(calendarId, race.availability());
        assertSingleFinalInterval(calendarId, ORIGINAL, "FREE");
    }

    @Test
    void returnsCommittedAvailabilityDuringSlotIntervalReplacement() throws Exception {
        String calendarId = createCalendar();
        Slot slot = createSlot(calendarId, ORIGINAL);

        RaceResult race = readAvailabilityDuring(calendarId, () -> replaceSlotStatus(calendarId, slot, REPLACEMENT));

        assertEquals(200, race.mutationStatus());
        assertValidAvailability(calendarId, race.availability());
        assertSingleFinalInterval(calendarId, REPLACEMENT, "FREE");
    }

    @Test
    void returnsCommittedAvailabilityDuringSlotStateChange() throws Exception {
        String calendarId = createCalendar();
        Slot slot = createSlot(calendarId, ORIGINAL);

        RaceResult race = readAvailabilityDuring(calendarId, () -> changeStateStatus(calendarId, slot, "BUSY"));

        assertEquals(200, race.mutationStatus());
        assertValidAvailability(calendarId, race.availability());
        assertSingleFinalInterval(calendarId, ORIGINAL, "BUSY");
    }

    @Test
    void returnsCommittedAvailabilityDuringSlotDeletion() throws Exception {
        String calendarId = createCalendar();
        Slot slot = createSlot(calendarId, ORIGINAL);

        RaceResult race = readAvailabilityDuring(calendarId, () -> deleteSlotStatus(calendarId, slot));

        assertEquals(204, race.mutationStatus());
        assertValidAvailability(calendarId, race.availability());
        assertEmptyFinalAvailability(calendarId);
    }

    @Test
    void returnsCommittedAvailabilityDuringMeetingConversion() throws Exception {
        String calendarId = createCalendar();
        Slot slot = createSlot(calendarId, ORIGINAL);

        RaceResult race = readAvailabilityDuring(calendarId, () -> convertToMeetingStatus(calendarId, slot));

        assertEquals(201, race.mutationStatus());
        assertValidAvailability(calendarId, race.availability());
        assertSingleFinalInterval(calendarId, ORIGINAL, "BUSY");
        Long meetingCount = jdbcTemplate.queryForObject(
                "select count(*) from meeting where slot_id = ?", Long.class, UUID.fromString(slot.id()));
        assertEquals(1L, meetingCount);
    }

    private RaceResult readAvailabilityDuring(String calendarId, Callable<Integer> mutation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MvcResult> read = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(availability(calendarId)).andReturn();
            });
            Future<Integer> write = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mutation.call();
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Availability reader and mutation contender must become ready");
            start.countDown();
            MvcResult availability = await(read, "Availability read did not complete");
            assertEquals(200, availability.getResponse().getStatus(), "Availability read must not return a server error");
            return new RaceResult(response(availability), await(write, "Concurrent mutation did not complete"));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertValidAvailability(String calendarId, JsonNode availability) {
        assertEquals(calendarId, availability.required("calendarId").asString());
        Instant frameStart = Instant.parse(availability.required("start").asString());
        Instant frameEnd = Instant.parse(availability.required("end").asString());
        assertTrue(frameEnd.isAfter(frameStart));

        JsonNode intervals = availability.required("intervals");
        assertTrue(intervals.isArray(), "Availability intervals must be an array");
        Instant previousEnd = null;
        for (int index = 0; index < intervals.size(); index++) {
            JsonNode interval = intervals.get(index);
            Instant start = Instant.parse(interval.required("start").asString());
            Instant end = Instant.parse(interval.required("end").asString());
            String state = interval.required("state").asString();
            assertTrue(end.isAfter(start), "Availability interval must have a positive duration");
            assertTrue(!start.isBefore(frameStart) && !end.isAfter(frameEnd),
                    "Availability interval must be contained by the requested frame");
            assertTrue(state.equals("FREE") || state.equals("BUSY"), "Availability state must be valid");
            if (previousEnd != null) {
                assertTrue(!start.isBefore(previousEnd), "Availability intervals must not overlap");
            }
            previousEnd = end;
        }
    }

    private void assertSingleFinalInterval(String calendarId, Interval expected, String expectedState) throws Exception {
        JsonNode availability = response(mockMvc.perform(availability(calendarId))
                .andExpect(status().isOk())
                .andReturn());
        assertValidAvailability(calendarId, availability);
        JsonNode intervals = availability.required("intervals");
        assertEquals(1, intervals.size());
        JsonNode interval = intervals.get(0);
        assertEquals(expected.start(), interval.required("start").asString());
        assertEquals(expected.end(), interval.required("end").asString());
        assertEquals(expectedState, interval.required("state").asString());
    }

    private void assertEmptyFinalAvailability(String calendarId) throws Exception {
        JsonNode availability = response(mockMvc.perform(availability(calendarId))
                .andExpect(status().isOk())
                .andReturn());
        assertValidAvailability(calendarId, availability);
        assertEquals(0, availability.required("intervals").size());
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "availability-race-" + suffix + "@example.com",
                                "name", "Availability Race " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("calendarId").asString();
    }

    private Slot createSlot(String calendarId, Interval interval) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("start", interval.start(), "end", interval.end()))))
                .andExpect(status().isCreated())
                .andReturn();
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(etag, "Slot creation response must contain ETag");
        return new Slot(response(result).required("id").asString(), etag);
    }

    private int createSlotStatus(String calendarId, Interval interval) throws Exception {
        return mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("start", interval.start(), "end", interval.end()))))
                .andReturn().getResponse().getStatus();
    }

    private int replaceSlotStatus(String calendarId, Slot slot, Interval replacement) throws Exception {
        return mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                        .header(HttpHeaders.IF_MATCH, slot.etag())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("start", replacement.start(), "end", replacement.end()))))
                .andReturn().getResponse().getStatus();
    }

    private int changeStateStatus(String calendarId, Slot slot, String state) throws Exception {
        return mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                        .header(HttpHeaders.IF_MATCH, slot.etag())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("state", state))))
                .andReturn().getResponse().getStatus();
    }

    private int deleteSlotStatus(String calendarId, Slot slot) throws Exception {
        return mockMvc.perform(delete("/calendars/{calendarId}/slots/{slotId}", calendarId, slot.id())
                        .header(HttpHeaders.IF_MATCH, slot.etag()))
                .andReturn().getResponse().getStatus();
    }

    private int convertToMeetingStatus(String calendarId, Slot slot) throws Exception {
        return mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slot.id())
                        .header(HttpHeaders.IF_MATCH, slot.etag())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Concurrent availability meeting",
                                "participants", List.of("alex@example.com")
                        ))))
                .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder availability(String calendarId) {
        return get("/calendars/{calendarId}/availability", calendarId)
                .queryParam("start", FRAME_START)
                .queryParam("end", FRAME_END);
    }

    private <T> T await(Future<T> response, String message) {
        try {
            return response.get(20, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(message, exception);
        }
    }

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Slot(String id, String etag) {
    }

    private record Interval(String start, String end) {
    }

    private record RaceResult(JsonNode availability, int mutationStatus) {
    }
}
