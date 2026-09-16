package com.masunov.task1.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String PROBLEM_DETAILS_REF = "#/components/schemas/ProblemDetails";

    @Bean
    OpenAPI task1OpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mini Doodle service API")
                        .version("1.0.0")
                        .description("""
                                Backend for the Mini Doodle scheduling service.

                                Conventions:
                                - All resource ids (user, calendar, slot, meeting) are server-generated UUIDs.
                                - All date-times use the ISO-8601 offset date-time format and are normalized to UTC.
                                - Slot and availability intervals are half-open: [start, end).
                                - Slots are versioned for optimistic concurrency. Every mutation that changes a slot
                                  requires an If-Match header carrying the quoted version from the slot's ETag, and the
                                  response ETag carries the new version.
                                - Errors are returned as RFC 9457 Problem Details JSON (application/problem+json).
                                """))
                .addServersItem(new Server().url("http://localhost:8080")
                        .description("Default local Compose deployment"))
                .components(new Components().addSchemas("ProblemDetails", problemDetailsSchema()));
    }

    private static Schema<?> problemDetailsSchema() {
        return new Schema<>()
                .type("object")
                .title("Problem Details")
                .description("RFC 9457 error response")
                .addProperty("type", new StringSchema()
                        .format("uri")
                        .description("URI reference identifying the problem type (default: about:blank)"))
                .addProperty("title", new StringSchema()
                        .description("Short, human-readable summary of the problem"))
                .addProperty("status", new IntegerSchema()
                        .description("HTTP status code generated for the problem"))
                .addProperty("detail", new StringSchema()
                        .description("Human-readable explanation specific to this occurrence"))
                .addProperty("instance", new StringSchema()
                        .format("uri")
                        .description("URI reference identifying the occurrence of the problem"));
    }
}