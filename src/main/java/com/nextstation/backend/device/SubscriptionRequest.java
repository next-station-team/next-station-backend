package com.nextstation.backend.device;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SubscriptionRequest(
        @NotNull UUID deviceId,
        @NotBlank @Size(max = 2048) String endpoint,
        @NotNull @Valid Keys keys) {
    public record Keys(@NotBlank @Size(max = 128) String p256dh,
                       @NotBlank @Size(max = 64) String auth) {}
}
