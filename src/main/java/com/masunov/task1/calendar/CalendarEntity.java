package com.masunov.task1.calendar;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "personal_calendar")
public class CalendarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    public CalendarEntity() {
    }

    public UUID getId() {
        return id;
    }
}
