package com.masunov.task1.availability;

import com.masunov.task1.calendar.CalendarNotFoundException;
import com.masunov.task1.calendar.CalendarRepository;
import com.masunov.task1.slot.SlotEntity;
import com.masunov.task1.slot.SlotRepository;
import com.masunov.task1.web.InvalidUserRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
class AvailabilityService {

    private final CalendarRepository calendarRepository;
    private final SlotRepository slotRepository;

    AvailabilityService(CalendarRepository calendarRepository, SlotRepository slotRepository) {
        this.calendarRepository = calendarRepository;
        this.slotRepository = slotRepository;
    }

    @Transactional(readOnly = true)
    AvailabilityResponse query(UUID calendarId, OffsetDateTime requestedStart, OffsetDateTime requestedEnd) {
        if (requestedStart == null || requestedEnd == null) {
            throw new InvalidUserRequestException("start and end are required");
        }

        Instant start = requestedStart.toInstant();
        Instant end = requestedEnd.toInstant();
        if (!end.isAfter(start)) {
            throw new InvalidUserRequestException("end must be after start");
        }

        if (!calendarRepository.existsById(calendarId)) {
            throw new CalendarNotFoundException(calendarId);
        }

        List<AvailabilityIntervalResponse> intervals = merge(
                slotRepository.findIntersectingSlots(calendarId, start, end),
                start,
                end
        );
        return new AvailabilityResponse(
                calendarId,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
                intervals
        );
    }

    private List<AvailabilityIntervalResponse> merge(List<SlotEntity> slots, Instant requestedStart, Instant requestedEnd) {
        List<AvailabilityIntervalResponse> merged = new ArrayList<>();
        for (SlotEntity slot : slots) {
            Instant start = slot.getStartAt().isBefore(requestedStart) ? requestedStart : slot.getStartAt();
            Instant end = slot.getEndAt().isAfter(requestedEnd) ? requestedEnd : slot.getEndAt();
            AvailabilityIntervalResponse interval = new AvailabilityIntervalResponse(
                    OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
                    slot.getState()
            );

            if (!merged.isEmpty()) {
                AvailabilityIntervalResponse previous = merged.getLast();
                if (previous.state() == interval.state() && !interval.start().isAfter(previous.end())) {
                    OffsetDateTime mergedEnd = interval.end().isAfter(previous.end()) ? interval.end() : previous.end();
                    merged.set(merged.size() - 1, new AvailabilityIntervalResponse(previous.start(), mergedEnd, previous.state()));
                    continue;
                }
            }
            merged.add(interval);
        }
        return List.copyOf(merged);
    }
}
