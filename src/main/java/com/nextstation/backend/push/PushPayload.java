package com.nextstation.backend.push;

import java.time.Instant;
import java.util.UUID;

/** tag is stable across retries/corrections; the service worker uses it for deduplication. */
public record PushPayload(UUID alarmId, UUID sessionId, String type, String title, String body,
                          String tag, Instant scheduledAt, Instant expiresAt,
                          Instant arrivalAt, int minutesBeforeArrival, String url) {
    public static PushPayload test(Instant now) {
        return new PushPayload(null, null, "TEST", "다음역 테스트 알림", "푸시 알림이 정상적으로 연결되었습니다.",
                "nextstation-test", now, now.plusSeconds(60), null, 0, "/");
    }
}
