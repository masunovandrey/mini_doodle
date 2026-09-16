package com.masunov.task1.slot;

import com.masunov.task1.web.OpenApiConfig;
import com.masunov.task1.web.SlotVersionPrecondition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Slots")
@RestController
@RequestMapping("/calendars/{calendarId}/slots")
class SlotController {

    private final SlotService slotService;

    SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    @PostMapping
    @Operation(summary = "Create a slot",
            description = "Creates a slot in the given calendar. The interval is half-open [start, end) and both " +
                    "date-times are normalized to UTC. The response ETag carries the initial slot version.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201",
                    description = "Slot created.",
                    headers = @Header(name = "ETag", description = "Quoted current slot version, e.g. \\\"0\\\".")),
            @ApiResponse(responseCode = "400",
                    description = "Request body is missing, malformed or invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "The calendar does not exist.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "409",
                    description = "The slot overlaps an existing slot of the calendar.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    ResponseEntity<SlotResponse> create(@PathVariable UUID calendarId, @RequestBody(required = false) SlotRequest request) {
        SlotResponse response = slotService.create(calendarId, request);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(SlotVersionPrecondition.format(response.version())).body(response);
    }

    @GetMapping("/{slotId}")
    @Operation(summary = "Get a slot")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "The requested slot.",
                    headers = @Header(name = "ETag", description = "Quoted current slot version, e.g. \\\"0\\\".")),
            @ApiResponse(responseCode = "400",
                    description = "calendarId or slotId is not a valid UUID.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "The calendar or slot does not exist.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    ResponseEntity<SlotResponse> get(@PathVariable UUID calendarId, @PathVariable UUID slotId) {
        SlotResponse response = slotService.get(calendarId, slotId);
        return ResponseEntity.ok().eTag(SlotVersionPrecondition.format(response.version())).body(response);
    }

    @PutMapping("/{slotId}")
    @Operation(summary = "Move or resize a slot",
            description = "Replaces the interval of the slot. Requires an If-Match header with the quoted version of " +
                    "the slot; the response ETag carries the new version.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Slot updated.",
                    headers = @Header(name = "ETag", description = "Quoted new slot version.")),
            @ApiResponse(responseCode = "400",
                    description = "Request body or If-Match header is missing, malformed or invalid. If-Match must be " +
                            "a quoted non-negative integer, e.g. \\\"0\\\".",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "The calendar or slot does not exist.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "409",
                    description = "The slot version does not match If-Match, the new interval overlaps an existing " +
                            "slot, or the slot is backed by a meeting.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    ResponseEntity<SlotResponse> update(
            @PathVariable UUID calendarId,
            @PathVariable UUID slotId,
            @RequestBody(required = false) SlotRequest request,
            @RequestHeader("If-Match") String ifMatch
    ) {
        SlotResponse response = slotService.update(calendarId, slotId, request, SlotVersionPrecondition.parse(ifMatch));
        return ResponseEntity.ok().eTag(SlotVersionPrecondition.format(response.version())).body(response);
    }

    @DeleteMapping("/{slotId}")
    @Operation(summary = "Delete a slot",
            description = "Removes the slot. Requires an If-Match header with the quoted version of the slot.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Slot deleted."),
            @ApiResponse(responseCode = "400",
                    description = "If-Match header is missing, malformed or invalid. If-Match must be a quoted " +
                            "non-negative integer, e.g. \\\"0\\\".",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "The calendar or slot does not exist.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "409",
                    description = "The slot version does not match If-Match, or the slot is backed by a meeting.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    ResponseEntity<Void> delete(
            @PathVariable UUID calendarId,
            @PathVariable UUID slotId,
            @RequestHeader("If-Match") String ifMatch
    ) {
        slotService.delete(calendarId, slotId, SlotVersionPrecondition.parse(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{slotId}")
    @Operation(summary = "Set a slot state",
            description = "Marks a slot FREE or BUSY. Requires an If-Match header with the quoted version of the slot; " +
                    "the response ETag carries the new version. Use the meeting endpoint to attach a meeting to a slot " +
                    "instead of marking it BUSY.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Slot state updated.",
                    headers = @Header(name = "ETag", description = "Quoted new slot version.")),
            @ApiResponse(responseCode = "400",
                    description = "Request body or If-Match header is missing, malformed or invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "The calendar or slot does not exist.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "409",
                    description = "The slot version does not match If-Match, or the slot is backed by a meeting.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    ResponseEntity<SlotResponse> changeState(
            @PathVariable UUID calendarId,
            @PathVariable UUID slotId,
            @RequestBody(required = false) SlotStateRequest request,
            @RequestHeader("If-Match") String ifMatch
    ) {
        SlotResponse response = slotService.changeState(calendarId, slotId, request, SlotVersionPrecondition.parse(ifMatch));
        return ResponseEntity.ok().eTag(SlotVersionPrecondition.format(response.version())).body(response);
    }
}