package com.masunov.task1.slot;

public class SlotOverlapException extends RuntimeException {

    SlotOverlapException() {
        super("The slot overlaps an existing slot in this calendar");
    }
}
