package com.nextstation.backend.device;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "device")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Device {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 2048)
    private String endpoint;
    @Column(nullable = false, length = 128)
    private String p256dh;
    @Column(nullable = false, length = 64)
    private String auth;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    private Instant lastTestAt;

    public Device(UUID id, String endpoint, String p256dh, String auth, Instant now) {
        this.id = id;
        this.createdAt = now;
        subscribe(endpoint, p256dh, auth, now);
    }

    public void subscribe(String endpoint, String p256dh, String auth, Instant now) {
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.active = true;
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        active = false;
        updatedAt = now;
    }

    public void recordTest(Instant now) {
        lastTestAt = now;
    }
}
