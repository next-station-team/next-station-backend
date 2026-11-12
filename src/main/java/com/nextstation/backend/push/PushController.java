package com.nextstation.backend.push;

import com.nextstation.backend.common.ApiException;
import com.nextstation.backend.config.PushProperties;
import com.nextstation.backend.device.DeviceService;
import com.nextstation.backend.device.SubscriptionRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushController {
    private final DeviceService devices;
    private final PushProperties properties;

    @GetMapping("/vapid-public-key")
    public Map<String, String> publicKey() {
        if (!properties.isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_DISABLED", "서버 푸시 설정이 필요합니다.");
        return Map.of("publicKey", properties.getPublicKey());
    }

    @PostMapping("/subscriptions")
    public DeviceService.SubscriptionResponse subscribe(@Valid @RequestBody SubscriptionRequest request) {
        return devices.subscribe(request);
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> test(@RequestHeader("X-Device-Id") UUID deviceId) {
        PushResult result = devices.testPush(deviceId);
        if (result.outcome() == PushResult.Outcome.EXPIRED_SUBSCRIPTION) {
            throw new ApiException(HttpStatus.GONE, result.code(), "푸시 구독을 다시 등록해 주세요.");
        }
        if (result.outcome() != PushResult.Outcome.ACCEPTED) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, result.code(), "푸시 제공자가 알림을 접수하지 못했습니다.");
        }
        return Map.of("status", "ACCEPTED");
    }
}
