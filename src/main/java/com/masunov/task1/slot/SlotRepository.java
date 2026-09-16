package com.masunov.task1.slot;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<SlotEntity, UUID> {

    Optional<SlotEntity> findByIdAndCalendarId(UUID id, UUID calendarId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select slot from SlotEntity slot where slot.id = :id and slot.calendar.id = :calendarId")
    Optional<SlotEntity> findByIdAndCalendarIdForUpdate(@Param("id") UUID id, @Param("calendarId") UUID calendarId);

    @Query("""
            select (count(slot) > 0)
            from SlotEntity slot
            where slot.calendar.id = :calendarId
              and slot.startAt < :endAt
              and slot.endAt > :startAt
              and (:excludedSlotId is null or slot.id <> :excludedSlotId)
            """)
    boolean existsOverlappingSlot(
            @Param("calendarId") UUID calendarId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt,
            @Param("excludedSlotId") UUID excludedSlotId
    );

    @Query("""
            select slot
            from SlotEntity slot
            where slot.calendar.id = :calendarId
              and slot.startAt < :endAt
              and slot.endAt > :startAt
            order by slot.startAt, slot.endAt
            """)
    List<SlotEntity> findIntersectingSlots(
            @Param("calendarId") UUID calendarId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt
    );
}
