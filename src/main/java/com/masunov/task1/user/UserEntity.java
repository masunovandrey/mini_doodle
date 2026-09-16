package com.masunov.task1.user;

import com.masunov.task1.calendar.CalendarEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "app_user")
class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @OneToOne(cascade = CascadeType.PERSIST, optional = false)
    @JoinColumn(name = "calendar_id", nullable = false, unique = true)
    private CalendarEntity calendar;

    protected UserEntity() {
    }

    UserEntity(String email, String name) {
        this.email = email;
        this.name = name;
        this.calendar = new CalendarEntity();
    }

    UUID getId() {
        return id;
    }

    String getEmail() {
        return email;
    }

    String getName() {
        return name;
    }

    CalendarEntity getCalendar() {
        return calendar;
    }
}
