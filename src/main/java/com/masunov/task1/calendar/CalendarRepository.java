package com.masunov.task1.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CalendarRepository extends JpaRepository<CalendarEntity, UUID> {
}
