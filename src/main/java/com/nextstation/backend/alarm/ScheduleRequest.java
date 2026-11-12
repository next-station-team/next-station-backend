package com.nextstation.backend.alarm;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ScheduleRequest(@NotNull Instant arrivalAt, Integer reminderMinutes) {
    public int effectiveReminderMinutes() { return reminderMinutes == null ? 5 : reminderMinutes; }
}
