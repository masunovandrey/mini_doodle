package com.masunov.task1.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MeetingRepository extends JpaRepository<MeetingEntity, UUID> {

    boolean existsBySlotId(UUID slotId);
}
