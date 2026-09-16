package com.masunov.task1.slot;

import com.masunov.task1.calendar.CalendarEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "calendar_slot")
public class SlotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calendar_id", nullable = false)
    private CalendarEntity calendar;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private SlotState state;

    @Version
    @Column(nullable = false)
    private long version;

    protected SlotEntity() {
    }

    SlotEntity(CalendarEntity calendar, Instant startAt, Instant endAt) {
        this.calendar = calendar;
        this.startAt = startAt;
        this.endAt = endAt;
        this.state = SlotState.FREE;
    }

    public UUID getId() {
        return id;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public SlotState getState() {
        return state;
    }

    public long getVersion() {
        return version;
    }

    void updateInterval(Instant startAt, Instant endAt) {
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public void changeState(SlotState state) {
        this.state = state;
    }
}
