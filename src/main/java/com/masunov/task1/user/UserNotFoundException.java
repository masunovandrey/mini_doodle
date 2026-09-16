package com.masunov.task1.user;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {

    UserNotFoundException(UUID userId) {
        super("User %s was not found".formatted(userId));
    }
}
