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

import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlotVersionContractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void emitsEtagsAndRejectsMissingOrStaleMutationPreconditions() throws Exception {
        String calendarId = createCalendar();
        MvcResult creation = mockMvc.perform(post("/calendars/{calendarId}/slots", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("start", "2026-12-21T09:00:00Z", "end", "2026-12-21T10:00:00Z"))))
                .andExpect(status().isCreated())
                .andReturn();
        String slotId = response(creation).required("id").asString();
        String initialEtag = requiredEtag(creation);

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(result -> assertNotNull(result.getResponse().getHeader(HttpHeaders.ETAG)));

        mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"BUSY\"}"))
                .andExpect(status().isBadRequest());

        MvcResult update = mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, initialEtag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"BUSY\"}"))
                .andExpect(status().isOk()).andReturn();
        String currentEtag = requiredEtag(update);

        mockMvc.perform(patch("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId)
                        .header(HttpHeaders.IF_MATCH, initialEtag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"FREE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/calendars/{calendarId}/slots/{slotId}", calendarId, slotId))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertEquals(currentEtag,
                        result.getResponse().getHeader(HttpHeaders.ETAG)));
    }

    private String createCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "etag-" + suffix + "@example.com", "name", "ETag " + suffix))))
                .andExpect(status().isCreated()).andReturn();
        return response(result).required("calendarId").asString();
    }

    private String requiredEtag(MvcResult result) {
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(etag, "Slot response must include ETag");
        return etag;
    }

    private String json(Map<String, String> body) throws Exception { return objectMapper.writeValueAsString(body); }
    private JsonNode response(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
}
