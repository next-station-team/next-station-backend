package com.nextstation.backend.alarm;

import com.nextstation.backend.config.AlarmProperties;
import com.nextstation.backend.device.Device;
import com.nextstation.backend.device.DeviceRepository;
import com.nextstation.backend.push.PushGateway;
import com.nextstation.backend.push.PushResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlarmDispatcher {
    private final AlarmRepository alarms;
    private final DeviceRepository devices;
    private final PushGateway gateway;
    private final AlarmProperties properties;
    private final Clock clock;

    @Transactional
    public void dispatch(UUID alarmId) {
        if (!gateway.enabled()) return;
        // Read only a scalar before the device lock. Do not cache a stale Alarm entity before locking.
        UUID deviceId = alarms.findDeviceId(alarmId).orElse(null);
        if (deviceId == null) return;
        Device device = devices.lockById(deviceId).orElse(null);
        if (device == null) return;
        Alarm alarm = alarms.findById(alarmId).orElse(null);
        Instant now = clock.instant();
        if (alarm == null || alarm.getStatus() != AlarmStatus.PENDING || alarm.getNextAttemptAt().isAfter(now)) return;
        if (!alarm.getExpiresAt().isAfter(now)) { alarm.expire(); return; }
        if (!device.isActive()) { alarm.fail("SUBSCRIPTION_EXPIRED"); return; }

        alarm.attempted();
        int ttl = (int) Math.min(60, Duration.between(now, alarm.getExpiresAt()).getSeconds());
        // A bounded HTTP call under the same device lock serializes send/update/cancel for this MVP.
        PushResult result = gateway.send(device, alarm.payload(), ttl);
        Instant completedAt = clock.instant();
        switch (result.outcome()) {
            case ACCEPTED -> alarm.sent(completedAt);
            case EXPIRED_SUBSCRIPTION -> {
                device.deactivate(completedAt);
                alarms.findByDeviceIdAndStatus(deviceId, AlarmStatus.PENDING)
                        .forEach(pending -> pending.fail("SUBSCRIPTION_EXPIRED"));
            }
            case FAILED -> alarm.fail(result.code());
            case RETRYABLE -> {
                Instant retryAt = completedAt.plus(properties.getRetryDelay().multipliedBy(alarm.getAttemptCount()));
                if (!retryAt.isBefore(alarm.getExpiresAt())) alarm.expire();
                else if (alarm.getAttemptCount() >= properties.getMaxAttempts()) alarm.fail(result.code());
                else alarm.retry(retryAt, result.code());
            }
        }
    }
}
