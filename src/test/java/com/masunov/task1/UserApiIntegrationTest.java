package com.masunov.task1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsUserWithOneCalendarAndReturnsTrimmedRepresentation() throws Exception {
        MvcResult result = createUser("  Ada@Example.com  ", "  Ada Lovelace  ");

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertDoesNotThrow(() -> UUID.fromString(response.path("id").asText()));
        assertDoesNotThrow(() -> UUID.fromString(response.path("calendarId").asText()));
    }

    @Test
    void retrievesCreatedUserById() throws Exception {
        MvcResult creation = createUser("grace.hopper@example.com", "Grace Hopper");
        String userId = objectMapper.readTree(creation.getResponse().getContentAsString())
                .required("id")
                .asText();

        mockMvc.perform(get("/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value("grace.hopper@example.com"))
                .andExpect(jsonPath("$.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.calendarId").isNotEmpty());
    }

    @Test
    void rejectsMalformedEmailWithProblemDetails() throws Exception {
        assertProblemDetailsForInvalidUser("not-an-email", "Valid Name");
    }

    @Test
    void rejectsBlankNameAfterTrimmingWithProblemDetails() throws Exception {
        assertProblemDetailsForInvalidUser("valid@example.com", "   ");
    }

    @Test
    void rejectsDuplicateEmailWithSameCase() throws Exception {
        createUser("duplicate@example.com", "First Name");

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userRequest("duplicate@example.com", "Second Name")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void permitsEmailsThatDifferOnlyByCase() throws Exception {
        createUser("CaseSensitive@example.com", "Case Sensitive One");

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userRequest("casesensitive@example.com", "Case Sensitive Two")))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsDuplicateNameIgnoringCase() throws Exception {
        createUser("first@example.com", "Name Duplicate Candidate");

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userRequest("second@example.com", "name duplicate candidate")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void returnsProblemDetailsForUnknownUser() throws Exception {
        mockMvc.perform(get("/users/{userId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    private MvcResult createUser(String email, String name) throws Exception {
        return mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userRequest(email, name)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email.trim()))
                .andExpect(jsonPath("$.name").value(name.trim()))
                .andExpect(jsonPath("$.calendarId").isNotEmpty())
                .andReturn();
    }

    private void assertProblemDetailsForInvalidUser(String email, String name) throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userRequest(email, name)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").isNotEmpty());
    }

    private String userRequest(String email, String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "name", name));
    }
}
