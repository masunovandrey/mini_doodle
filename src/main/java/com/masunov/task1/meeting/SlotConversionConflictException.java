package com.masunov.task1.meeting;

public class SlotConversionConflictException extends RuntimeException {

    SlotConversionConflictException() {
        super("Only a free, meeting-free slot can be converted to a meeting");
    }
}
