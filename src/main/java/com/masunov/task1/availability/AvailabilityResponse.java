package com.masunov.task1.availability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

record AvailabilityResponse(
        UUID calendarId,
        OffsetDateTime start,
        OffsetDateTime end,
        List<AvailabilityIntervalResponse> intervals
) {
}
