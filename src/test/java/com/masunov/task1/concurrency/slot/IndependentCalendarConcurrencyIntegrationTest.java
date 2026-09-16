package com.masunov.task1.concurrency.slot;

import com.masunov.task1.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IndependentCalendarConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void acceptsSimultaneousValidSlotWritesInIndependentCalendars() throws Exception {
        List<String> calendarIds = List.of(createCalendar(), createCalendar(), createCalendar());
        CountDownLatch ready = new CountDownLatch(calendarIds.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(calendarIds.size());

        try {
            List<Future<Integer>> writes = calendarIds.stream()
                    .map(calendarId -> createSlot(executor, ready, start, calendarId))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS), "All independent-calendar contenders must become ready");
            start.countDown();

            List<Integer> statuses = writes.stream()
                    .map(this::awaitStatus)
                    .toList();
            assertEquals(List.of(201, 201, 201), statuses,
                    "Independent calendar writes must all succeed: " + statuses);
        } finally {
            executor.shutdownNow();
        }

        for (String calendarId : calendarIds) {
            Long slotCount = jdbcTemplate.queryForObject(
                    "select count(*) from calendar_slot where calendar_id = ?",
                    Long.class,
                    UUID.fromString(calendarId)
            );
            assertEquals(1L, slotCount, "Independent calendar must retain its created slot");
        }
    }

    private Future<Integer> createSlot(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            String calendarId
    ) {
        return executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("start", "2026-12-27T09:00:00Z", "end", "2026-12-27T10:00:00Z"))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        });
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "independent-calendar-" + suffix + "@example.com",
                                "name", "Independent Calendar " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("calendarId").asText();
    }

    private int awaitStatus(Future<Integer> response) {
        try {
            return response.get(20, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Independent calendar write did not complete", exception);
        }
    }

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
