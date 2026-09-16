package com.masunov.task1.meeting;

import com.masunov.task1.web.OpenApiConfig;
import com.masunov.task1.web.SlotVersionPrecondition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Meetings")
@RestController
@RequestMapping("/calendars/{calendarId}/slots/{slotId}/meeting")
class MeetingController {

    private final MeetingService meetingService;

    MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @PostMapping
    @Operation(summary = "Convert a slot into a meeting",
            description = "Creates a meeting for the slot and marks it BUSY. Requires an If-Match header with the " +
                    "quoted version of the slot. A slot can only be converted once.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201",
                    description = "Meeting created and the slot is now BUSY."),
            @ApiResponse(responseCode = "400",
                    description = "Request body or If-Match header is missing, malformed or invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "The calendar or slot does not exist.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "409",
                    description = "The slot version does not match If-Match, a meeting conversion races with another " +
                            "conversion or state change, or the slot already has a meeting.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    ResponseEntity<MeetingResponse> convert(
            @PathVariable UUID calendarId,
            @PathVariable UUID slotId,
            @RequestBody(required = false) MeetingRequest request,
            @RequestHeader("If-Match") String ifMatch
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(meetingService.convert(calendarId, slotId, request, SlotVersionPrecondition.parse(ifMatch)));
    }
}