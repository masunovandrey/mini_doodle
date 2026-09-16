package com.masunov.task1.slot;

import com.masunov.task1.calendar.CalendarEntity;
import com.masunov.task1.calendar.CalendarNotFoundException;
import com.masunov.task1.calendar.CalendarRepository;
import com.masunov.task1.meeting.MeetingBackedSlotMutationException;
import com.masunov.task1.meeting.MeetingRepository;
import com.masunov.task1.web.InvalidUserRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
class SlotService {

    private final CalendarRepository calendarRepository;
    private final SlotRepository slotRepository;
    private final MeetingRepository meetingRepository;
    private final EntityManager entityManager;

    SlotService(
            CalendarRepository calendarRepository,
            SlotRepository slotRepository,
            MeetingRepository meetingRepository,
            EntityManager entityManager
    ) {
        this.calendarRepository = calendarRepository;
        this.slotRepository = slotRepository;
        this.meetingRepository = meetingRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    SlotResponse create(UUID calendarId, SlotRequest request) {
        CalendarEntity calendar = calendarRepository.findById(calendarId)
                .orElseThrow(() -> new CalendarNotFoundException(calendarId));
        Interval interval = intervalFrom(request);
        rejectOverlap(calendarId, interval, null);

        try {
            return SlotResponse.from(slotRepository.saveAndFlush(new SlotEntity(calendar, interval.start(), interval.end())));
        } catch (DataIntegrityViolationException exception) {
            throw new SlotOverlapException();
        }
    }

    @Transactional(readOnly = true)
    SlotResponse get(UUID calendarId, UUID slotId) {
        return SlotResponse.from(findSlot(calendarId, slotId));
    }

    @Transactional
    SlotResponse update(UUID calendarId, UUID slotId, SlotRequest request, long expectedVersion) {
        SlotEntity slot = findSlotForUpdate(calendarId, slotId);
        requireCurrentVersion(slot, expectedVersion);
        rejectMeetingBackedSlotMutation(slotId);
        Interval interval = intervalFrom(request);
        rejectOverlap(calendarId, interval, slotId);
        slot.updateInterval(interval.start(), interval.end());

        try {
            return SlotResponse.from(slotRepository.saveAndFlush(slot));
        } catch (DataIntegrityViolationException exception) {
            throw new SlotOverlapException();
        }
    }

    @Transactional
    void delete(UUID calendarId, UUID slotId, long expectedVersion) {
        SlotEntity slot = findSlotForUpdate(calendarId, slotId);
        requireCurrentVersion(slot, expectedVersion);
        rejectMeetingBackedSlotMutation(slotId);
        slotRepository.delete(slot);
        slotRepository.flush();
    }

    @Transactional
    SlotResponse changeState(UUID calendarId, UUID slotId, SlotStateRequest request, long expectedVersion) {
        if (request == null || request.state() == null) {
            throw new InvalidUserRequestException("state is required");
        }

        SlotEntity slot = findSlotForUpdate(calendarId, slotId);
        requireCurrentVersion(slot, expectedVersion);
        rejectMeetingBackedSlotMutation(slotId);
        boolean stateChanged = slot.getState() != request.state();
        slot.changeState(request.state());
        if (!stateChanged) {
            entityManager.lock(slot, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        return SlotResponse.from(slotRepository.saveAndFlush(slot));
    }

    private SlotEntity findSlot(UUID calendarId, UUID slotId) {
        return slotRepository.findByIdAndCalendarId(slotId, calendarId)
                .orElseThrow(() -> new SlotNotFoundException(slotId));
    }

    private SlotEntity findSlotForUpdate(UUID calendarId, UUID slotId) {
        return slotRepository.findByIdAndCalendarIdForUpdate(slotId, calendarId)
                .orElseThrow(() -> new SlotNotFoundException(slotId));
    }

    private Interval intervalFrom(SlotRequest request) {
        if (request == null || request.start() == null || request.end() == null) {
            throw new InvalidUserRequestException("start and end are required");
        }

        Instant start = request.start().toInstant();
        Instant end = request.end().toInstant();
        if (!end.isAfter(start)) {
            throw new InvalidUserRequestException("end must be after start");
        }
        return new Interval(start, end);
    }

    private void rejectOverlap(UUID calendarId, Interval interval, UUID excludedSlotId) {
        if (slotRepository.existsOverlappingSlot(calendarId, interval.start(), interval.end(), excludedSlotId)) {
            throw new SlotOverlapException();
        }
    }

    private void rejectMeetingBackedSlotMutation(UUID slotId) {
        if (meetingRepository.existsBySlotId(slotId)) {
            throw new MeetingBackedSlotMutationException();
        }
    }

    private void requireCurrentVersion(SlotEntity slot, long expectedVersion) {
        if (slot.getVersion() != expectedVersion) {
            throw new StaleSlotVersionException();
        }
    }

    private record Interval(Instant start, Instant end) {
    }
}
