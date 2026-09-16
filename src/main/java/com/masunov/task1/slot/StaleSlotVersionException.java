package com.masunov.task1.slot;

public class StaleSlotVersionException extends RuntimeException {

    public StaleSlotVersionException() {
        super("The slot has changed; retrieve its latest version before retrying");
    }
}
