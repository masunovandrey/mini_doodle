package com.masunov.task1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publishesOpenApiDocumentThatCoversTheWholeContract() throws Exception {
        JsonNode api = openApiDocument();

        assertTrue(api.path("openapi").asString().startsWith("3."), "OpenAPI 3.x spec version is advertised");
        assertEquals("Mini Doodle service API", api.path("info").path("title").asString());

        JsonNode paths = api.path("paths");
        assertTrue(paths.path("/users").has("post"), "POST /users is documented");
        assertTrue(paths.path("/users/{userId}").path("get").isObject(), "GET /users/{userId} is documented");
        assertTrue(paths.path("/calendars/{calendarId}/slots").has("post"), "POST slots is documented");

        JsonNode slotById = paths.path("/calendars/{calendarId}/slots/{slotId}");
        assertTrue(slotById.has("get"), "GET slot is documented");
        assertTrue(slotById.has("put"), "PUT slot is documented");
        assertTrue(slotById.has("patch"), "PATCH slot is documented");
        assertTrue(slotById.has("delete"), "DELETE slot is documented");
        assertTrue(paths.path("/calendars/{calendarId}/slots/{slotId}/meeting").has("post"), "POST meeting is documented");
        assertTrue(paths.path("/calendars/{calendarId}/availability").has("get"), "GET availability is documented");
    }

    @Test
    void documentsSchemasRequestAndResponseTypes() throws Exception {
        JsonNode schemas = openApiDocument().path("components").path("schemas");

        Set<String> expected = Set.of(
                "ProblemDetails",
                "CreateUserRequest", "UserResponse",
                "SlotRequest", "SlotResponse", "SlotStateRequest",
                "MeetingRequest", "MeetingResponse",
                "AvailabilityResponse", "AvailabilityIntervalResponse"
        );
        expected.forEach(name -> assertTrue(schemas.has(name), "Schema " + name + " is documented"));

        assertProblemDetails(schemas.path("ProblemDetails"));

        JsonNode slotResponse = schemas.path("SlotResponse");
        assertEquals("date-time", slotResponse.path("properties").path("start").path("format").asString());
        assertEquals("date-time", slotResponse.path("properties").path("end").path("format").asString());
        assertEquals("uuid", slotResponse.path("properties").path("id").path("format").asString());
        assertEquals("integer", slotResponse.path("properties").path("version").path("type").asString());
        assertEquals(Set.of("FREE", "BUSY"),
                enumValues(slotResponse.path("properties").path("state"), schemas),
                "Slot state enum is documented (inline or by reference)");
    }

    @Test
    void documentsUuidPathParametersAndIfMatchHeader() throws Exception {
        JsonNode api = openApiDocument();

        JsonNode userGetParameters = api.path("paths").path("/users/{userId}").path("get").path("parameters");
        JsonNode userId = parameterNamed(userGetParameters, "userId");
        assertEquals("path", userId.path("in").asString());
        assertTrue(userId.path("required").asBoolean());
        assertEquals("uuid", userId.path("schema").path("format").asString());

        JsonNode slotPutParameters = api.path("paths").path("/calendars/{calendarId}/slots/{slotId}").path("put").path("parameters");
        JsonNode ifMatch = parameterNamed(slotPutParameters, "If-Match");
        assertEquals("header", ifMatch.path("in").asString());
        assertTrue(ifMatch.path("required").asBoolean());
    }

    @Test
    void documentsProblemDetailsStatusCodesForEveryEndpoint() throws Exception {
        JsonNode api = openApiDocument();

        assertResponses(api, "/users", "post", Set.of("201", "400", "409"));
        assertResponses(api, "/users/{userId}", "get", Set.of("200", "400", "404"));
        assertResponses(api, "/calendars/{calendarId}/slots", "post", Set.of("201", "400", "404", "409"));
        assertResponses(api, "/calendars/{calendarId}/slots/{slotId}", "get", Set.of("200", "400", "404"));
        assertResponses(api, "/calendars/{calendarId}/slots/{slotId}", "put", Set.of("200", "400", "404", "409"));
        assertResponses(api, "/calendars/{calendarId}/slots/{slotId}", "patch", Set.of("200", "400", "404", "409"));
        assertResponses(api, "/calendars/{calendarId}/slots/{slotId}", "delete", Set.of("204", "400", "404", "409"));
        assertResponses(api, "/calendars/{calendarId}/slots/{slotId}/meeting", "post", Set.of("201", "400", "404", "409"));
        assertResponses(api, "/calendars/{calendarId}/availability", "get", Set.of("200", "400", "404"));
    }

    @Test
    void problemResponsesReferenceTheProblemDetailsSchema() throws Exception {
        JsonNode api = openApiDocument();
        JsonNode emailConflict = api.path("paths").path("/users").path("post").path("responses").path("409");

        JsonNode content = emailConflict.path("content").path("application/problem+json");
        assertTrue(content.isObject(), "409 response carries application/problem+json content");
        assertEquals("#/components/schemas/ProblemDetails", content.path("schema").path("$ref").asString());
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void servesOpenApiJson() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    private JsonNode openApiDocument() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(json);
    }

    private void assertProblemDetails(JsonNode problemDetails) {
        assertTrue(problemDetails.isObject(), "ProblemDetails schema is an object");
        JsonNode properties = problemDetails.path("properties");
        Set.of("type", "title", "status", "detail", "instance")
                .forEach(name -> assertTrue(properties.has(name), "ProblemDetails documents " + name));
    }

    private JsonNode parameterNamed(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asString())) {
                return parameter;
            }
        }
        throw new AssertionError("Missing parameter " + name);
    }

    private void assertResponses(JsonNode api, String path, String operation, Set<String> expectedStatuses) {
        JsonNode responses = api.path("paths").path(path).path(operation).path("responses");
        Set<String> actualStatuses = responseStatuses(responses);
        assertTrue(actualStatuses.containsAll(expectedStatuses),
                operation.toUpperCase() + " " + path + " documents " + expectedStatuses + " but was " + actualStatuses);
    }

    private Set<String> responseStatuses(JsonNode responses) {
        return new java.util.TreeSet<>(responses.propertyNames());
    }

    private Set<String> enumValues(JsonNode schema, JsonNode schemas) {
        JsonNode resolved = schema.has("$ref") ? schemas.path(refName(schema.path("$ref").asString())) : schema;
        Set<String> values = new java.util.TreeSet<>();
        resolved.path("enum").forEach(value -> values.add(value.asString()));
        return values;
    }

    private String refName(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }
}