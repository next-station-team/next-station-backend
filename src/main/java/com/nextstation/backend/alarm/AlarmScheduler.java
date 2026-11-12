package com.nextstation.backend.alarm;

import com.nextstation.backend.config.AlarmProperties;
import com.nextstation.backend.push.PushGateway;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.alarm.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AlarmScheduler {
    private final AlarmRepository alarms;
    private final AlarmDispatcher dispatcher;
    private final PushGateway gateway;
    private final AlarmProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.alarm.poll-delay-ms:1000}")
    public void dispatchDueAlarms() {
        if (!gateway.enabled()) return;
        // Persistent PENDING rows are rediscovered automatically after every restart.
        for (UUID id : alarms.findDueIds(AlarmStatus.PENDING, clock.instant(), PageRequest.of(0, properties.getBatchSize()))) {
            try {
                dispatcher.dispatch(id);
            } catch (RuntimeException ex) {
                log.error("Alarm transaction failed: alarmId={}, errorType={}", id, ex.getClass().getSimpleName());
            }
        }
    }
}
