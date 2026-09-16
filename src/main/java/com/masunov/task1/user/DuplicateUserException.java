package com.masunov.task1.user;

public class DuplicateUserException extends RuntimeException {

    DuplicateUserException(String message) {
        super(message);
    }
}
