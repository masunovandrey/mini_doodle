package com.masunov.task1.web;

public class InvalidUserRequestException extends RuntimeException {

    public InvalidUserRequestException(String message) {
        super(message);
    }
}
