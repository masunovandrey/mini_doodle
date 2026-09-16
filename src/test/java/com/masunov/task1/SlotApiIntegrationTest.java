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

import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
class SlotApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsFreeSlotWithServerGeneratedIdAndUtcInterval() throws Exception {
        String calendarId = createCalendar();

        MvcResult result = createSlot(
                calendarId,
                "2026-03-10T09:00:00+02:00",
                "2026-03-10T09:30:00+02:00"
        );

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertDoesNotThrow(() -> UUID.fromString(response.required("id").asText()));
        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, response.required("id").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(response.required("id").asText()))
                .andExpect(jsonPath("$.start").value("2026-03-10T07:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-03-10T07:30:00Z"))
                .andExpect(jsonPath("$.state").value("FREE"));
    }

    @Test
    void permitsAdjacentSlotsAcrossEquivalentOffsets() throws Exception {
        String calendarId = createCalendar();

        createSlot(calendarId, "2026-04-01T10:00:00+02:00", "2026-04-01T11:00:00+02:00");

        mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-04-01T10:00:00+01:00", "2026-04-01T11:00:00+01:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.start").value("2026-04-01T09:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-04-01T10:00:00Z"))
                .andExpect(jsonPath("$.state").value("FREE"));
    }

    @Test
    void rejectsOverlappingCreationAndLeavesExistingSlotUnchanged() throws Exception {
        String calendarId = createCalendar();
        JsonNode existing = slotResponse(createSlot(
                calendarId,
                "2026-05-12T09:00:00Z",
                "2026-05-12T10:00:00Z"
        ));

        mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-05-12T09:30:00Z", "2026-05-12T10:30:00Z")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, existing.required("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value("2026-05-12T09:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-05-12T10:00:00Z"))
                .andExpect(jsonPath("$.state").value("FREE"));
    }

    @Test
    void rejectsOverlappingUpdateAndLeavesSlotUnchanged() throws Exception {
        String calendarId = createCalendar();
        createSlot(calendarId, "2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        JsonNode toUpdate = slotResponse(createSlot(
                calendarId,
                "2026-06-08T10:00:00Z",
                "2026-06-08T11:00:00Z"
        ));

        mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, toUpdate.required("id").asText())
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, toUpdate.required("id").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-06-08T09:30:00Z", "2026-06-08T10:30:00Z")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, toUpdate.required("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value("2026-06-08T10:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-06-08T11:00:00Z"));
    }

    @Test
    void updatesIntervalWhilePreservingState() throws Exception {
        String calendarId = createCalendar();
        JsonNode created = slotResponse(createSlot(
                calendarId,
                "2026-07-04T09:00:00Z",
                "2026-07-04T10:00:00Z"
        ));
        String slotId = created.required("id").asText();

        changeState(calendarId, slotId, "BUSY");

        mockMvc.perform(put("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-07-04T11:00:00+02:00", "2026-07-04T12:30:00+02:00")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(slotId))
                .andExpect(jsonPath("$.start").value("2026-07-04T09:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-07-04T10:30:00Z"))
                .andExpect(jsonPath("$.state").value("BUSY"));
    }

    @Test
    void changesMeetingFreeSlotBetweenBusyAndFree() throws Exception {
        String calendarId = createCalendar();
        String slotId = slotResponse(createSlot(
                calendarId,
                "2026-08-15T13:00:00Z",
                "2026-08-15T14:00:00Z"
        )).required("id").asText();

        changeState(calendarId, slotId, "BUSY")
                .andExpect(jsonPath("$.state").value("BUSY"));
        changeState(calendarId, slotId, "FREE")
                .andExpect(jsonPath("$.state").value("FREE"));
    }

    @Test
    void deletesSlotAndSubsequentReadReturnsProblemDetails() throws Exception {
        String calendarId = createCalendar();
        String slotId = slotResponse(createSlot(
                calendarId,
                "2026-09-20T09:00:00Z",
                "2026-09-20T10:00:00Z"
        )).required("id").asText();

        mockMvc.perform(delete("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsInvalidIntervalAndStatePayloadsWithProblemDetails() throws Exception {
        String calendarId = createCalendar();

        mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"not-a-date\",\"end\":\"2026-10-01T11:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        String slotId = slotResponse(createSlot(
                calendarId,
                "2026-10-02T09:00:00Z",
                "2026-10-02T10:00:00Z"
        )).required("id").asText();

        mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void returnsNotFoundForUnknownCalendarSlotAndCrossCalendarSlot() throws Exception {
        String calendarId = createCalendar();
        String slotId = slotResponse(createSlot(
                calendarId,
                "2026-11-10T09:00:00Z",
                "2026-11-10T10:00:00Z"
        )).required("id").asText();
        String otherCalendarId = createCalendar();

        mockMvc.perform(post("/calendars/{calendarId}/slots", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest("2026-11-11T09:00:00Z", "2026-11-11T10:00:00Z")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", otherCalendarId, slotId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    private MvcResult createSlot(String calendarId, String start, String end) throws Exception {
        return mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotRequest(start, end)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.start").isNotEmpty())
                .andExpect(jsonPath("$.end").isNotEmpty())
                .andExpect(jsonPath("$.state").value("FREE"))
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions changeState(String calendarId, String slotId, String state)
            throws Exception {
        return mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, etag(calendarId, slotId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("state", state))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(slotId));
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "slot-" + suffix + "@example.com",
                                "name", "Slot User " + suffix
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return slotResponse(result).required("calendarId").asText();
    }

    private JsonNode slotResponse(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

    private String slotRequest(String start, String end) throws Exception {
        return objectMapper.writeValueAsString(Map.of("start", start, "end", end));
    }
}
