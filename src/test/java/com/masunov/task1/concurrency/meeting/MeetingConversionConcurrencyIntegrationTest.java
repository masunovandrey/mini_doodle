package com.masunov.task1.concurrency.meeting;

import com.masunov.task1.TestcontainersConfiguration;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MeetingConversionConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allowsEitherValidWinnerWhenConversionRacesWithMarkingItFree() throws Exception {
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
                                    .content(meetingRequest()))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });
                Future<Integer> markFree = executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                                    .header(HttpHeaders.IF_MATCH, initialEtag)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"state\":\"FREE\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });

                assertTrue(ready.await(10, TimeUnit.SECONDS), "Both contenders must become ready");
                start.countDown();
                int conversionStatus = conversion.get(20, TimeUnit.SECONDS);
                int markFreeStatus = markFree.get(20, TimeUnit.SECONDS);

                assertTrue(
                        (conversionStatus == 201 && markFreeStatus == 409)
                                || (conversionStatus == 409 && markFreeStatus == 200),
                        () -> "Unexpected conversion/PATCH FREE outcome: conversion=" + conversionStatus
                                + ", patch=" + markFreeStatus);

                Long meetingCount = jdbcTemplate.queryForObject(
                        "select count(*) from meeting where slot_id = ?",
                        Long.class,
                        UUID.fromString(slotId)
                );
                assertEquals(conversionStatus == 201 ? 1L : 0L, meetingCount,
                        "The meeting count must match the conversion outcome");

                String expectedState = conversionStatus == 201 ? "BUSY" : "FREE";
                mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value(expectedState));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void keepsMeetingBackedSlotImmutableWhenConversionRacesWithReplacingItsInterval() throws Exception {
        int attempts = 10;
        String originalStart = "2026-12-20T09:00:00Z";
        String originalEnd = "2026-12-20T10:00:00Z";
        String updatedStart = "2026-12-20T11:00:00Z";
        String updatedEnd = "2026-12-20T12:00:00Z";

        for (int attempt = 0; attempt < attempts; attempt++) {
            String calendarId = createCalendar();
            String slotId = createFreeSlot(calendarId, originalStart, originalEnd);
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
                                    .content(meetingRequest()))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });
                Future<Integer> replaceInterval = executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                                    .header(HttpHeaders.IF_MATCH, initialEtag)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(slotRequest(updatedStart, updatedEnd)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });

                assertTrue(ready.await(10, TimeUnit.SECONDS), "Both contenders must become ready");
                start.countDown();
                int conversionStatus = conversion.get(20, TimeUnit.SECONDS);
                int replaceIntervalStatus = replaceInterval.get(20, TimeUnit.SECONDS);

                assertTrue(
                        (conversionStatus == 201 && replaceIntervalStatus == 409)
                                || (conversionStatus == 409 && replaceIntervalStatus == 200),
                        () -> "Unexpected conversion/PUT outcome: conversion=" + conversionStatus
                                + ", put=" + replaceIntervalStatus);

                Long meetingCount = jdbcTemplate.queryForObject(
                        "select count(*) from meeting where slot_id = ?",
                        Long.class,
                        UUID.fromString(slotId)
                );
                assertEquals(conversionStatus == 201 ? 1L : 0L, meetingCount);

                String expectedStart = replaceIntervalStatus == 200 ? updatedStart : originalStart;
                String expectedEnd = replaceIntervalStatus == 200 ? updatedEnd : originalEnd;
                String expectedState = conversionStatus == 201 ? "BUSY" : "FREE";
                mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.start").value(expectedStart))
                        .andExpect(jsonPath("$.end").value(expectedEnd))
                        .andExpect(jsonPath("$.state").value(expectedState));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void leavesNoOrphanMeetingWhenConversionRacesWithDeletion() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
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
                                    .contentType(MediaType.APPLICATION_JSON).content(meetingRequest()))
                            .andReturn().getResponse().getStatus();
                });
                Future<Integer> deletion = executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(delete("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                                    .header(HttpHeaders.IF_MATCH, initialEtag))
                            .andReturn().getResponse().getStatus();
                });

                assertTrue(ready.await(10, TimeUnit.SECONDS), "Both contenders must become ready");
                start.countDown();
                int conversionStatus = conversion.get(20, TimeUnit.SECONDS);
                int deletionStatus = deletion.get(20, TimeUnit.SECONDS);
                assertTrue((conversionStatus == 201 && deletionStatus == 409)
                                || (conversionStatus == 404 && deletionStatus == 204),
                        () -> "Unexpected conversion/DELETE outcome: conversion=" + conversionStatus
                                + ", delete=" + deletionStatus);

                Long meetingCount = jdbcTemplate.queryForObject(
                        "select count(*) from meeting where slot_id = ?", Long.class, UUID.fromString(slotId));
                if (conversionStatus == 201) {
                    assertEquals(1L, meetingCount);
                    mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("BUSY"));
                } else {
                    assertEquals(0L, meetingCount);
                    mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                            .andExpect(status().isNotFound());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "concurrency-" + suffix + "@example.com",
                                "name", "Concurrency User " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("calendarId").asString();
    }

    private String createFreeSlot(String calendarId) throws Exception {
        return createFreeSlot(calendarId, "2026-12-20T09:00:00Z", "2026-12-20T10:00:00Z");
    }

    private String createFreeSlot(String calendarId, String start, String end) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "start", start,
                                "end", end
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("id").asString();
    }

    private String meetingRequest() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("title", "Concurrent booking");
        request.put("description", null);
        request.put("participants", List.of("alex@example.com"));
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
