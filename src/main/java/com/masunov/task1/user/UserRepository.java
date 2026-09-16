package com.masunov.task1.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface UserRepository extends JpaRepository<UserEntity, UUID> {

    boolean existsByEmail(String email);

    boolean existsByNameIgnoreCase(String name);
}
