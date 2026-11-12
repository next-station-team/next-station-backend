package com.nextstation.backend.alarm;

import com.nextstation.backend.device.Device;
import com.nextstation.backend.push.PushPayload;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "alarm", uniqueConstraints = @UniqueConstraint(name = "uk_alarm_device_session_kind",
        columnNames = {"device_id", "session_id", "kind"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Alarm {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;
    @Column(nullable = false)
    private UUID sessionId;
    @Column(nullable = false)
    private Instant scheduledAt;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private Instant arrivalAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AlarmKind kind;
    @Column(nullable = false)
    private int minutesBeforeArrival;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AlarmStatus status;
    @Column(nullable = false)
    private int attemptCount;
    @Column(nullable = false)
    private Instant nextAttemptAt;
    private Instant sentAt;
    @Column(length = 64)
    private String lastErrorCode;

    public Alarm(Device device, UUID sessionId, Instant scheduledAt, Instant expiresAt,
                 Instant arrivalAt, AlarmKind kind, int minutesBeforeArrival, Instant now) {
        id = UUID.randomUUID();
        this.device = device;
        this.sessionId = sessionId;
        this.scheduledAt = scheduledAt.isBefore(now) ? now : scheduledAt;
        this.expiresAt = expiresAt;
        this.arrivalAt = arrivalAt;
        this.kind = kind;
        this.minutesBeforeArrival = minutesBeforeArrival;
        nextAttemptAt = this.scheduledAt;
        status = expiresAt.isAfter(now) ? AlarmStatus.PENDING : AlarmStatus.EXPIRED;
    }

    public void expire() { status = AlarmStatus.EXPIRED; }
    public void attempted() { attemptCount++; }
    public void sent(Instant now) { status = AlarmStatus.SENT; sentAt = now; lastErrorCode = null; }
    public void fail(String code) { status = AlarmStatus.FAILED; lastErrorCode = code; }
    public void retry(Instant at, String code) { nextAttemptAt = at; lastErrorCode = code; }

    public PushPayload payload() {
        boolean finish = kind == AlarmKind.FINISH;
        return new PushPayload(id, sessionId, kind.name(), finish ? "이제 마무리할 시간이에요" : "하차를 준비해 주세요",
                finish ? "진행 중인 콘텐츠를 저장하고 마무리해 주세요." : "곧 도착합니다. 소지품을 챙겨 주세요.",
                "nextstation-" + sessionId + "-" + kind, scheduledAt, expiresAt, arrivalAt,
                minutesBeforeArrival, "/");
    }
}
