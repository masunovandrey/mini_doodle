package com.masunov.task1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CalendarAvailabilityApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void returnsFrameClippedAndStateMergedAvailabilityIncludingMeetingBusySlots() throws Exception {
        String calendarId = createCalendar();
        createSlot(calendarId, "2026-12-15T08:30:00Z", "2026-12-15T09:30:00Z");
        createSlot(calendarId, "2026-12-15T09:30:00Z", "2026-12-15T10:00:00Z");
        String manuallyBusySlot = createSlot(calendarId, "2026-12-15T10:00:00Z", "2026-12-15T11:00:00Z");
        markBusy(calendarId, manuallyBusySlot);
        String meetingSlot = createSlot(calendarId, "2026-12-15T11:00:00Z", "2026-12-15T12:00:00Z");
        convertToMeeting(calendarId, meetingSlot);
        createSlot(calendarId, "2026-12-15T16:30:00Z", "2026-12-15T17:30:00Z");

        mockMvc.perform(availability(calendarId, "2026-12-15T09:00:00Z", "2026-12-15T17:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.calendarId").value(calendarId))
                .andExpect(jsonPath("$.start").value("2026-12-15T09:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-12-15T17:00:00Z"))
                .andExpect(jsonPath("$.intervals.length()").value(3))
                .andExpect(jsonPath("$.intervals[0].start").value("2026-12-15T09:00:00Z"))
                .andExpect(jsonPath("$.intervals[0].end").value("2026-12-15T10:00:00Z"))
                .andExpect(jsonPath("$.intervals[0].state").value("FREE"))
                .andExpect(jsonPath("$.intervals[1].start").value("2026-12-15T10:00:00Z"))
                .andExpect(jsonPath("$.intervals[1].end").value("2026-12-15T12:00:00Z"))
                .andExpect(jsonPath("$.intervals[1].state").value("BUSY"))
                .andExpect(jsonPath("$.intervals[2].start").value("2026-12-15T16:30:00Z"))
                .andExpect(jsonPath("$.intervals[2].end").value("2026-12-15T17:00:00Z"))
                .andExpect(jsonPath("$.intervals[2].state").value("FREE"));
    }

    @Test
    void returnsEmptyIntervalsForEmptyCalendarAndForSlotsOnlyTouchingFrameBoundaries() throws Exception {
        String emptyCalendarId = createCalendar();
        String calendarId = createCalendar();
        createSlot(calendarId, "2026-12-16T08:00:00Z", "2026-12-16T09:00:00Z");
        createSlot(calendarId, "2026-12-16T10:00:00Z", "2026-12-16T11:00:00Z");

        mockMvc.perform(availability(emptyCalendarId, "2026-12-16T09:00:00Z", "2026-12-16T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarId").value(emptyCalendarId))
                .andExpect(jsonPath("$.intervals").isEmpty());

        mockMvc.perform(availability(calendarId, "2026-12-16T09:00:00Z", "2026-12-16T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervals").isEmpty());
    }

    @Test
    void excludesSlotsFromOtherCalendars() throws Exception {
        String requestedCalendarId = createCalendar();
        String otherCalendarId = createCalendar();
        createSlot(otherCalendarId, "2026-12-17T09:00:00Z", "2026-12-17T10:00:00Z");

        mockMvc.perform(availability(requestedCalendarId, "2026-12-17T08:00:00Z", "2026-12-17T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarId").value(requestedCalendarId))
                .andExpect(jsonPath("$.intervals").isEmpty());
    }

    @Test
    void rejectsInvalidFramesAndUnknownCalendarsWithProblemDetails() throws Exception {
        String calendarId = createCalendar();

        mockMvc.perform(get("/calendars/{calendarId}/availability", calendarId)
                        .queryParam("end", "2026-12-18T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(availability(calendarId, "2026-12-18T10:00:00Z", "2026-12-18T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(availability(calendarId, "not-a-date", "2026-12-18T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(availability(UUID.randomUUID().toString(), "2026-12-18T09:00:00Z", "2026-12-18T10:00:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder availability(
            String calendarId,
            String start,
            String end
    ) {
        return get("/calendars/{calendarId}/availability", calendarId)
                .queryParam("start", start)
                .queryParam("end", end);
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "availability-" + suffix + "@example.com",
                                "name", "Availability User " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).required("calendarId").asText();
    }

    private String createSlot(String calendarId, String start, String end) throws Exception {
        MvcResult result = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("start", start, "end", end))))
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

    private void convertToMeeting(String calendarId, String slotId) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("title", "Availability meeting");
        request.put("description", null);
        request.put("participants", List.of("alex@example.com"));

        mockMvc.perform(post("/calendars/{calendarId}/slots/{slotId}/meeting", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
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
