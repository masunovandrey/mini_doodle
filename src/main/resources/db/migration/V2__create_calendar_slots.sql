CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE calendar_slot (
    id UUID PRIMARY KEY,
    calendar_id UUID NOT NULL REFERENCES personal_calendar (id),
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(4) NOT NULL,
    CONSTRAINT calendar_slot_end_after_start CHECK (end_at > start_at),
    CONSTRAINT calendar_slot_state CHECK (state IN ('FREE', 'BUSY')),
    CONSTRAINT calendar_slot_no_overlap EXCLUDE USING gist (
        calendar_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    )
);

CREATE INDEX calendar_slot_calendar_start_idx ON calendar_slot (calendar_id, start_at);
