package com.masunov.task1.slot;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

record SlotResponse(UUID id, OffsetDateTime start, OffsetDateTime end, SlotState state, long version) {

    static SlotResponse from(SlotEntity slot) {
        return new SlotResponse(
                slot.getId(),
                OffsetDateTime.ofInstant(slot.getStartAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(slot.getEndAt(), ZoneOffset.UTC),
                slot.getState(),
                slot.getVersion()
        );
    }
}
