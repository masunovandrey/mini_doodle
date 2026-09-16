package com.masunov.task1.user;

import java.util.UUID;

record UserResponse(UUID id, String email, String name, UUID calendarId) {

    static UserResponse from(UserEntity user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getCalendar().getId()
        );
    }
}
