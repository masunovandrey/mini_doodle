package com.masunov.task1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MeetingConversionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void convertsFreeSlotAtomicallyAndReturnsPersistedMeetingDetails() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);

        MvcResult result = convert(
                calendarId,
                slotId,
                "  Design review  ",
                "Discuss the proposed design",
                List.of("  alex@example.com  ", "sam@example.com")
        );

        JsonNode meeting = response(result);
        assertDoesNotThrow(() -> UUID.fromString(meeting.required("id").asText()));
        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(slotId))
                .andExpect(jsonPath("$.state").value("BUSY"));
    }

    @Test
    void acceptsMeetingWithoutOptionalDescription() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);

        mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(meetingRequest("Planning", null, List.of("alex@example.com"))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.slotId").value(slotId))
                .andExpect(jsonPath("$.title").value("Planning"))
                .andExpect(jsonPath("$.participants[0]").value("alex@example.com"))
                .andExpect(jsonPath("$.slotState").value("BUSY"));
    }

    @Test
    void rejectsInvalidMeetingInputWithoutChangingTheFreeSlot() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);

        assertInvalidMeeting(calendarId, slotId, "{\"participants\":[\"alex@example.com\"]}");
        assertInvalidMeeting(calendarId, slotId, meetingRequest("   ", null, List.of("alex@example.com")));
        assertInvalidMeeting(calendarId, slotId, meetingRequest("Planning", null, List.of()));
        assertInvalidMeeting(calendarId, slotId, meetingRequest("Planning", null, List.of("   ")));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FREE"));

        convert(calendarId, slotId, "Planning", null, List.of("alex@example.com"));
    }

    @Test
    void rejectsConversionOfManuallyBusySlot() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);
        markBusy(calendarId, slotId);

        assertConversionConflict(calendarId, slotId);

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSY"));
    }

    @Test
    void rejectsRepeatedConversionWithoutCreatingAnotherMeeting() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);

        convert(calendarId, slotId, "Planning", null, List.of("alex@example.com"));
        assertConversionConflict(calendarId, slotId);

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSY"));
    }

    @Test
    void preventsAllManualMutationsOfMeetingBackedSlot() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);
        convert(calendarId, slotId, "Planning", null, List.of("alex@example.com"));

        mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"FREE\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-12-01T11:00:00Z", "2026-12-01T12:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(delete("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSY"));
    }

    @Test
    void returnsNotFoundForUnknownOrCrossCalendarSlot() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);
        String otherCalendarId = createCalendar();

        assertConversionNotFound(calendarId, UUID.randomUUID().toString());
        assertConversionNotFound(otherCalendarId, slotId);
    }

    @Test
    void allowsExactlyOneConcurrentConversion() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);
        String initialEtag = etag(calendarId, slotId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                                    .header(HttpHeaders.IF_MATCH, initialEtag)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(meetingRequest("Concurrent booking", null, List.of("alex@example.com"))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<Integer> statuses = List.of(
                    responses.getFirst().get(20, TimeUnit.SECONDS),
                    responses.get(1).get(20, TimeUnit.SECONDS)
            );

            assertEquals(1, statuses.stream().filter(statusCode -> statusCode == 201).count());
            assertEquals(1, statuses.stream().filter(statusCode -> statusCode == 409).count());
        } finally {
            executor.shutdownNow();
        }

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSY"));
    }

    @Test
    void persistsExactlyOneMeetingWhenSeveralConversionsRaceForTheSameSlot() throws Exception {
        String calendarId = createCalendar();
        String slotId = createFreeSlot(calendarId);
        String initialEtag = etag(calendarId, slotId);
        int contenders = 4;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);

        try {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int attempt = 0; attempt < contenders; attempt++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                                    .header(HttpHeaders.IF_MATCH, initialEtag)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(meetingRequest("Concurrent booking", null, List.of("alex@example.com"))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Not every conversion contender became ready");
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : responses) {
                statuses.add(response.get(20, TimeUnit.SECONDS));
            }

            assertTrue(statuses.stream().allMatch(statusCode -> statusCode == 201 || statusCode == 409),
                    () -> "Unexpected concurrent conversion statuses: " + statuses);
            assertEquals(1, statuses.stream().filter(statusCode -> statusCode == 201).count());
            assertEquals(contenders - 1L, statuses.stream().filter(statusCode -> statusCode == 409).count());
        } finally {
            executor.shutdownNow();
        }

        Long meetingCount = jdbcTemplate.queryForObject(
                "select count(*) from meeting where slot_id = ?",
                Long.class,
                UUID.fromString(slotId)
        );
        assertEquals(1L, meetingCount);
        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSY"));
    }

    @Test
    void allowsEitherValidWinnerWhenConversionRacesWithMarkingTheSameSlotBusy() throws Exception {
        int attempts = 10;

        for (int attempt = 0; attempt < attempts; attempt++) {
            String calendarId = createCalendar();
            String slotId = createFreeSlot(calendarId);
            String initialEtag = etag(calendarId, slotId);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            try {
                Future<Integer> conversion = executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                                    .header(HttpHeaders.IF_MATCH, initialEtag)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(meetingRequest("Concurrent booking", null, List.of("alex@example.com"))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });
                Future<Integer> markBusy = executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                                    .header(HttpHeaders.IF_MATCH, initialEtag)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"state\":\"BUSY\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });

                assertTrue(ready.await(10, TimeUnit.SECONDS), "Both contenders must become ready");
                start.countDown();
                int conversionStatus = conversion.get(20, TimeUnit.SECONDS);
                int markBusyStatus = markBusy.get(20, TimeUnit.SECONDS);

                assertTrue(
                        (conversionStatus == 201 && markBusyStatus == 409)
                                || (conversionStatus == 409 && markBusyStatus == 200),
                        () -> "Invalid conversion/PATCH BUSY outcome: conversion=" + conversionStatus
                                + ", patch=" + markBusyStatus
                );

                Long meetingCount = jdbcTemplate.queryForObject(
                        "select count(*) from meeting where slot_id = ?",
                        Long.class,
                        UUID.fromString(slotId)
                );
                if (conversionStatus == 201) {
                    assertEquals(1L, meetingCount);
                } else {
                    assertEquals(0L, meetingCount);
                }
                mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("BUSY"));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private MvcResult convert(
            String calendarId,
            String slotId,
            String title,
            String description,
            List<String> participants
    ) throws Exception {
        return mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(meetingRequest(title, description, participants)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.slotId").value(slotId))
                .andExpect(jsonPath("$.title").value(title.trim()))
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.participants[0]").value(participants.getFirst().trim()))
                .andExpect(jsonPath("$.slotState").value("BUSY"))
                .andReturn();
    }

    private void assertInvalidMeeting(String calendarId, String slotId, String request) throws Exception {
        mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    private void assertConversionConflict(String calendarId, String slotId) throws Exception {
        mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(meetingRequest("Planning", null, List.of("alex@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));
    }

    private void assertConversionNotFound(String calendarId, String slotId) throws Exception {
        mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(meetingRequest("Planning", null, List.of("alex@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "meeting-" + suffix + "@example.com",
                                "name", "Meeting User " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("calendarId").asText();
    }

    private String createFreeSlot(String calendarId) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-12-01T09:00:00Z", "2026-12-01T10:00:00Z")))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("id").asText();
    }

    private void markBusy(String calendarId, String slotId) throws Exception {
        mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"BUSY\"}"))
                .andExpect(status().isOk());
    }

    private String meetingRequest(String title, String description, List<String> participants) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("title", title);
        request.put("description", description);
        request.put("participants", participants);
        return objectMapper.writeValueAsString(request);
    }

    private String slotRequest(String start, String end) throws Exception {
        return objectMapper.writeValueAsString(Map.of("start", start, "end", end));
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String etag(String calendarId, String slotId) throws Exception {
        String value = mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        if (value == null) {
            throw new AssertionError("Slot response must contain ETag");
        }
        return value;
    }
}
