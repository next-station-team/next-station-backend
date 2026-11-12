package com.nextstation.backend.device;

import com.nextstation.backend.common.ApiException;
import com.nextstation.backend.push.PushGateway;
import com.nextstation.backend.push.PushPayload;
import com.nextstation.backend.push.PushResult;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {
    private final DeviceRepository repository;
    private final SubscriptionValidator validator;
    private final PushGateway gateway;
    private final Clock clock;

    public record SubscriptionResponse(UUID deviceId, boolean active, Instant createdAt, Instant updatedAt) {}

    @Transactional
    public SubscriptionResponse subscribe(SubscriptionRequest request) {
        validator.validate(request);
        if (repository.existsByEndpointAndIdNot(request.endpoint(), request.deviceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBSCRIPTION_ALREADY_REGISTERED", "다른 기기 ID에 등록된 구독입니다.");
        }
        Instant now = clock.instant();
        Device device = repository.lockById(request.deviceId()).orElseGet(() ->
                new Device(request.deviceId(), request.endpoint(), request.keys().p256dh(), request.keys().auth(), now));
        device.subscribe(request.endpoint(), request.keys().p256dh(), request.keys().auth(), now);
        repository.saveAndFlush(device);
        return new SubscriptionResponse(device.getId(), device.isActive(), device.getCreatedAt(), device.getUpdatedAt());
    }

    @Transactional
    public PushResult testPush(UUID deviceId) {
        if (!gateway.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_DISABLED", "서버 푸시 설정이 필요합니다.");
        Device device = requireActiveLocked(deviceId);
        Instant now = clock.instant();
        if (device.getLastTestAt() != null && device.getLastTestAt().plusSeconds(30).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TEST_PUSH_RATE_LIMITED", "테스트 알림은 30초 후 다시 보낼 수 있습니다.");
        }
        device.recordTest(now);
        PushResult result = gateway.send(device, PushPayload.test(now), 60);
        if (result.outcome() == PushResult.Outcome.EXPIRED_SUBSCRIPTION) device.deactivate(now);
        return result;
    }

    public Device requireActiveLocked(UUID deviceId) {
        Device device = repository.lockById(deviceId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "먼저 푸시 구독을 등록해 주세요."));
        if (!device.isActive()) throw new ApiException(HttpStatus.GONE, "SUBSCRIPTION_EXPIRED", "푸시 구독을 다시 등록해 주세요.");
        return device;
    }
}
