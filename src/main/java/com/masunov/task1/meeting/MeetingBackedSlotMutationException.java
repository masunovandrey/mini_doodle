package com.masunov.task1.meeting;

public class MeetingBackedSlotMutationException extends RuntimeException {

    public MeetingBackedSlotMutationException() {
        super("A meeting-backed slot cannot be modified, deleted, or have its state changed");
    }
}
