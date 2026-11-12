CREATE TABLE IF NOT EXISTS device (
    id UUID PRIMARY KEY,
    endpoint VARCHAR(2048) NOT NULL UNIQUE,
    p256dh VARCHAR(128) NOT NULL,
    auth VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_test_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS alarm (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES device(id),
    session_id UUID NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    arrival_at TIMESTAMP WITH TIME ZONE NOT NULL,
    kind VARCHAR(20) NOT NULL,
    minutes_before_arrival INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    CONSTRAINT uk_alarm_device_session_kind UNIQUE (device_id, session_id, kind)
);
CREATE INDEX IF NOT EXISTS idx_alarm_due ON alarm(status, next_attempt_at);
