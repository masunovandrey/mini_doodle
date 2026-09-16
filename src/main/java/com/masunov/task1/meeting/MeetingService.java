package com.masunov.task1.meeting;

import com.masunov.task1.slot.SlotEntity;
import com.masunov.task1.slot.SlotNotFoundException;
import com.masunov.task1.slot.SlotRepository;
import com.masunov.task1.slot.SlotState;
import com.masunov.task1.slot.StaleSlotVersionException;
import com.masunov.task1.web.InvalidUserRequestException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class MeetingService {

    private final SlotRepository slotRepository;
    private final MeetingRepository meetingRepository;

    MeetingService(SlotRepository slotRepository, MeetingRepository meetingRepository) {
        this.slotRepository = slotRepository;
        this.meetingRepository = meetingRepository;
    }

    @Transactional
    MeetingResponse convert(UUID calendarId, UUID slotId, MeetingRequest request, long expectedVersion) {
        ValidatedMeetingRequest validatedRequest = validate(request);
        SlotEntity slot = slotRepository.findByIdAndCalendarIdForUpdate(slotId, calendarId)
                .orElseThrow(() -> new SlotNotFoundException(slotId));

        if (slot.getVersion() != expectedVersion) {
            throw new StaleSlotVersionException();
        }

        if (slot.getState() != SlotState.FREE || meetingRepository.existsBySlotId(slotId)) {
            throw new SlotConversionConflictException();
        }

        slot.changeState(SlotState.BUSY);
        try {
            MeetingEntity meeting = meetingRepository.saveAndFlush(new MeetingEntity(
                    slot,
                    validatedRequest.title(),
                    validatedRequest.description(),
                    validatedRequest.participants()
            ));
            return MeetingResponse.from(meeting);
        } catch (DataIntegrityViolationException exception) {
            throw new SlotConversionConflictException();
        }
    }

    private ValidatedMeetingRequest validate(MeetingRequest request) {
        if (request == null) {
            throw new InvalidUserRequestException("Meeting details are required");
        }

        String title = requiredTrimmedValue(request.title(), "title");
        if (request.participants() == null || request.participants().isEmpty()) {
            throw new InvalidUserRequestException("At least one participant is required");
        }

        List<String> participants = request.participants().stream()
                .map(participant -> requiredTrimmedValue(participant, "participant"))
                .toList();
        return new ValidatedMeetingRequest(title, request.description(), participants);
    }

    private String requiredTrimmedValue(String value, String fieldName) {
        if (value == null || value.strip().isEmpty()) {
            throw new InvalidUserRequestException("%s is required".formatted(fieldName));
        }
        return value.strip();
    }

    private record ValidatedMeetingRequest(String title, String description, List<String> participants) {
    }
}
