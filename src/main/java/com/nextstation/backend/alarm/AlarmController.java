package com.nextstation.backend.alarm;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions/{sessionId}/alarms")
@RequiredArgsConstructor
public class AlarmController {
    private final AlarmService service;

    @PutMapping
    public List<AlarmResponse> schedule(@RequestHeader("X-Device-Id") UUID deviceId,
                                        @PathVariable UUID sessionId, @Valid @RequestBody ScheduleRequest request) {
        return service.schedule(deviceId, sessionId, request);
    }

    @GetMapping
    public List<AlarmResponse> list(@RequestHeader("X-Device-Id") UUID deviceId, @PathVariable UUID sessionId) {
        return service.list(deviceId, sessionId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@RequestHeader("X-Device-Id") UUID deviceId, @PathVariable UUID sessionId) {
        service.cancel(deviceId, sessionId);
    }
}
