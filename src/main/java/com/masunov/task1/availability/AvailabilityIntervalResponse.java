package com.masunov.task1.availability;

import com.masunov.task1.slot.SlotState;

import java.time.OffsetDateTime;

record AvailabilityIntervalResponse(OffsetDateTime start, OffsetDateTime end, SlotState state) {
}
