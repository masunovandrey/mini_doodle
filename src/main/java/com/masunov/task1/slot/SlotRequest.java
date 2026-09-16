package com.masunov.task1.slot;

import java.time.OffsetDateTime;

record SlotRequest(OffsetDateTime start, OffsetDateTime end) {
}
