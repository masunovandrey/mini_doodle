package com.masunov.task1.calendar;

import java.util.UUID;

public class CalendarNotFoundException extends RuntimeException {

    public CalendarNotFoundException(UUID calendarId) {
        super("Calendar %s was not found".formatted(calendarId));
    }
}
