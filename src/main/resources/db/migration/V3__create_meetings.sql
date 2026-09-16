CREATE TABLE meeting (
    id UUID PRIMARY KEY,
    slot_id UUID NOT NULL UNIQUE REFERENCES calendar_slot (id),
    title VARCHAR(255) NOT NULL,
    description TEXT
);

CREATE TABLE meeting_participant (
    meeting_id UUID NOT NULL REFERENCES meeting (id) ON DELETE CASCADE,
    participant_position INTEGER NOT NULL,
    participant VARCHAR(255) NOT NULL,
    PRIMARY KEY (meeting_id, participant_position)
);
