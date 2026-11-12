package com.nextstation.backend.alarm;

import com.nextstation.backend.common.ApiException;
import com.nextstation.backend.config.AlarmProperties;
import com.nextstation.backend.device.Device;
import com.nextstation.backend.device.DeviceRepository;
import com.nextstation.backend.device.DeviceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlarmService {
    private final AlarmRepository alarms;
    private final DeviceRepository devices;
    private final DeviceService deviceService;
    private final AlarmProperties properties;
    private final Clock clock;

    @Transactional
    public List<AlarmResponse> schedule(UUID deviceId, UUID sessionId, ScheduleRequest request) {
        Instant now = clock.instant();
        int minutes = request.effectiveReminderMinutes();
        if (!Set.of(3, 5, 7).contains(minutes)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REMINDER_MINUTES", "알림 시점은 3, 5, 7분 중 하나입니다.");
        }
        if (!request.arrivalAt().isAfter(now) || request.arrivalAt().isAfter(now.plus(Duration.ofDays(1)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARRIVAL_TIME", "도착 시각은 현재 이후 24시간 이내여야 합니다.");
        }
        Device device = deviceService.requireActiveLocked(deviceId);
        List<Alarm> previous = alarms.findByDeviceIdAndSessionIdOrderByScheduledAt(deviceId, sessionId);
        Set<AlarmKind> sentKinds = previous.stream().filter(a -> a.getStatus() == AlarmStatus.SENT)
                .map(Alarm::getKind).collect(Collectors.toSet());
        // Delete and insert in one transaction. Keep sent receipts to avoid repeating a stage on correction.
        alarms.deleteAll(previous.stream().filter(a -> a.getStatus() != AlarmStatus.SENT).toList());
        alarms.flush();
        Instant prepareAt = request.arrivalAt().minus(Duration.ofMinutes(1)).minus(properties.getSafetyMargin());
        Instant finishAt = request.arrivalAt().minus(Duration.ofMinutes(minutes)).minus(properties.getSafetyMargin());
        if (!sentKinds.contains(AlarmKind.FINISH)) {
            alarms.save(new Alarm(device, sessionId, finishAt, prepareAt, request.arrivalAt(), AlarmKind.FINISH, minutes, now));
        }
        if (!sentKinds.contains(AlarmKind.PREPARE)) {
            alarms.save(new Alarm(device, sessionId, prepareAt, request.arrivalAt(), request.arrivalAt(), AlarmKind.PREPARE, 1, now));
        }
        alarms.flush();
        return responses(deviceId, sessionId);
    }

    @Transactional(readOnly = true)
    public List<AlarmResponse> list(UUID deviceId, UUID sessionId) {
        if (!devices.existsById(deviceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "등록되지 않은 기기입니다.");
        }
        return responses(deviceId, sessionId);
    }

    @Transactional
    public void cancel(UUID deviceId, UUID sessionId) {
        // Cancellation also works for expired subscriptions; an unknown device is an idempotent no-op.
        if (devices.lockById(deviceId).isEmpty()) return;
        alarms.deleteAll(alarms.findByDeviceIdAndSessionIdOrderByScheduledAt(deviceId, sessionId));
    }

    private List<AlarmResponse> responses(UUID deviceId, UUID sessionId) {
        return alarms.findByDeviceIdAndSessionIdOrderByScheduledAt(deviceId, sessionId)
                .stream().map(AlarmResponse::from).toList();
    }
}
