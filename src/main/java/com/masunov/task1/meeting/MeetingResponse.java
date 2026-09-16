package com.masunov.task1.meeting;

import com.masunov.task1.slot.SlotState;

import java.util.List;
import java.util.UUID;

record MeetingResponse(
        UUID id,
        UUID slotId,
        String title,
        String description,
        List<String> participants,
        SlotState slotState
) {

    static MeetingResponse from(MeetingEntity meeting) {
        return new MeetingResponse(
                meeting.getId(),
                meeting.getSlot().getId(),
                meeting.getTitle(),
                meeting.getDescription(),
                meeting.getParticipants(),
                meeting.getSlot().getState()
        );
    }
}
