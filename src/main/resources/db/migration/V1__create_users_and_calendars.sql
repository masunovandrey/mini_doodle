CREATE TABLE personal_calendar (
    id UUID PRIMARY KEY
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    calendar_id UUID NOT NULL UNIQUE REFERENCES personal_calendar (id)
);

CREATE UNIQUE INDEX app_user_name_lower_unique ON app_user (LOWER(name));
