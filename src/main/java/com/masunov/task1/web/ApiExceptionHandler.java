package com.masunov.task1.web;

import com.masunov.task1.calendar.CalendarNotFoundException;
import com.masunov.task1.meeting.MeetingBackedSlotMutationException;
import com.masunov.task1.meeting.SlotConversionConflictException;
import com.masunov.task1.slot.SlotNotFoundException;
import com.masunov.task1.slot.SlotOverlapException;
import com.masunov.task1.slot.StaleSlotVersionException;
import com.masunov.task1.user.DuplicateUserException;
import com.masunov.task1.user.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InvalidUserRequestException.class)
    ResponseEntity<ProblemDetail> handleInvalidUserRequest(InvalidUserRequestException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Request body must be valid JSON");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleInvalidRequestParameter() {
        return problem(HttpStatus.BAD_REQUEST, "Request parameters are invalid");
    }

    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<ProblemDetail> handleDuplicateUser(DuplicateUserException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ProblemDetail> handleMissingUser(UserNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({CalendarNotFoundException.class, SlotNotFoundException.class})
    ResponseEntity<ProblemDetail> handleMissingSlotResource(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(SlotOverlapException.class)
    ResponseEntity<ProblemDetail> handleSlotOverlap(SlotOverlapException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({SlotConversionConflictException.class, MeetingBackedSlotMutationException.class})
    ResponseEntity<ProblemDetail> handleSlotMeetingConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(StaleSlotVersionException.class)
    ResponseEntity<ProblemDetail> handleStaleSlotVersion(StaleSlotVersionException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
