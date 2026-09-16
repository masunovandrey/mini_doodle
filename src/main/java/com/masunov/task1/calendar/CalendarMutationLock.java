package com.masunov.task1.calendar;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Serializes all mutations within one calendar by taking a PostgreSQL
 * transaction-scoped advisory lock keyed by the calendar id.
 *
 * Concurrency problem solved: two transactions updating different slots of the
 * same calendar to overlapping new ranges each took a ShareLock on the other's
 * (speculative) index entry while detecting the exclusion-constraint conflict,
 * producing an AB-BA deadlock (500) instead of a clean 409.
 *
 * Holding this lock for the whole mutation transaction gives the caller the
 * guarantee that no other mutation runs concurrently in the same calendar: the
 * pre-flight overlap check then observes the winner's committed state and the
 * loser returns a clean 409. Cross-calendar mutations are unaffected (they use
 * different advisory keys). The lock is released automatically when the
 * transaction commits or rolls back.
 */
@Component
public class CalendarMutationLock {

    private final JdbcTemplate jdbcTemplate;

    public CalendarMutationLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void acquire(UUID calendarId) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?::text, 0))")) {
                statement.setString(1, calendarId.toString());
                statement.execute();
            }
            return null;
        });
    }
}