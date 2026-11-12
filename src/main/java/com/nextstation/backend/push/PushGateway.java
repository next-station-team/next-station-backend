package com.nextstation.backend.push;

import com.nextstation.backend.device.Device;

public interface PushGateway {
    boolean enabled();
    PushResult send(Device device, PushPayload payload, int ttlSeconds);
}
