package com.nextstation.backend.alarm;

import java.time.Instant;
import java.util.UUID;

public record AlarmResponse(UUID alarmId, UUID sessionId, AlarmKind type, Instant scheduledAt,
                            Instant expiresAt, Instant arrivalAt, int minutesBeforeArrival,
                            AlarmStatus status, int attemptCount, Instant sentAt, String lastErrorCode) {
    public static AlarmResponse from(Alarm alarm) {
        return new AlarmResponse(alarm.getId(), alarm.getSessionId(), alarm.getKind(), alarm.getScheduledAt(),
                alarm.getExpiresAt(), alarm.getArrivalAt(), alarm.getMinutesBeforeArrival(), alarm.getStatus(),
                alarm.getAttemptCount(), alarm.getSentAt(), alarm.getLastErrorCode());
    }
}
