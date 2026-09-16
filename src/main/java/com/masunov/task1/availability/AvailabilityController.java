package com.masunov.task1.availability;

import com.masunov.task1.web.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@Tag(name = "Availability")
@RestController
@RequestMapping("/calendars/{calendarId}/availability")
class AvailabilityController {

    private final AvailabilityService availabilityService;

    AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping
    @Operation(summary = "Query availability",
            description = "Returns the intervals of the calendar within the half-open window [start, end). The window " +
                    "boundaries are ISO-8601 offset date-times; the window must start before it ends.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The availability intervals of the requested calendar."),
            @ApiResponse(responseCode = "400",
                    description = "start or end is missing, not a valid ISO-8601 offset date-time, or the window " +
                            "does not satisfy start < end.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "The calendar does not exist.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    AvailabilityResponse query(
            @PathVariable UUID calendarId,
            @RequestParam OffsetDateTime start,
            @RequestParam OffsetDateTime end
    ) {
        return availabilityService.query(calendarId, start, end);
    }
}