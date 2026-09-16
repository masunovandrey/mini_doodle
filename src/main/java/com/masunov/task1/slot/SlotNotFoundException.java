package com.masunov.task1.slot;

import java.util.UUID;

public class SlotNotFoundException extends RuntimeException {

    public SlotNotFoundException(UUID slotId) {
        super("Slot %s was not found".formatted(slotId));
    }
}
