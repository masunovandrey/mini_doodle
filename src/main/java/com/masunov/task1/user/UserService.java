package com.masunov.task1.user;

import com.masunov.task1.web.InvalidUserRequestException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
class UserService {

    private final UserRepository userRepository;
    private final Validator validator;

    UserService(UserRepository userRepository, Validator validator) {
        this.userRepository = userRepository;
        this.validator = validator;
    }

    @Transactional
    UserResponse create(CreateUserRequest request) {
        String email = trimmedRequiredValue(request, request == null ? null : request.email(), "email");
        String name = trimmedRequiredValue(request, request == null ? null : request.name(), "name");

        validateEmail(email);

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateUserException("A user with this email already exists");
        }
        if (userRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateUserException("A user with this name already exists");
        }

        try {
            return UserResponse.from(userRepository.saveAndFlush(new UserEntity(email, name)));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateUserException("A user with this email or name already exists");
        }
    }

    @Transactional(readOnly = true)
    UserResponse get(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private String trimmedRequiredValue(CreateUserRequest request, String value, String fieldName) {
        if (request == null || value == null) {
            throw new InvalidUserRequestException("%s is required".formatted(fieldName));
        }

        String trimmedValue = value.strip();
        if (trimmedValue.isEmpty()) {
            throw new InvalidUserRequestException("%s must not be blank".formatted(fieldName));
        }
        return trimmedValue;
    }

    private void validateEmail(String email) {
        Set<ConstraintViolation<EmailValue>> violations = validator.validate(new EmailValue(email));
        if (!violations.isEmpty()) {
            throw new InvalidUserRequestException("email must be syntactically valid");
        }
    }

    private record EmailValue(@Email String value) {
    }
}
