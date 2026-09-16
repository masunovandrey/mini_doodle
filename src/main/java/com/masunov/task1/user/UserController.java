package com.masunov.task1.user;

import com.masunov.task1.web.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Users")
@RestController
@RequestMapping("/users")
class UserController {

    private final UserService userService;

    UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @Operation(summary = "Create a user",
            description = "Creates a user together with its default calendar. Both ids are server-generated UUIDs.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201",
                    description = "User and default calendar created. Body contains the user id, email, name and default calendar id."),
            @ApiResponse(responseCode = "400",
                    description = "Request body is missing, malformed or invalid.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "409",
                    description = "Email is already in use.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    ResponseEntity<UserResponse> create(@RequestBody(required = false) CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get a user")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The requested user."),
            @ApiResponse(responseCode = "400",
                    description = "userId is not a valid UUID.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF))),
            @ApiResponse(responseCode = "404",
                    description = "No user exists for the given id.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = OpenApiConfig.PROBLEM_DETAILS_REF)))
    })
    UserResponse get(@PathVariable UUID userId) {
        return userService.get(userId);
    }
}