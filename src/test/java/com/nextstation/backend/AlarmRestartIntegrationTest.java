package com.nextstation.backend;

import com.nextstation.backend.alarm.*;
import com.nextstation.backend.config.AlarmProperties;
import com.nextstation.backend.device.DeviceService;
import com.nextstation.backend.push.PushGateway;
import com.nextstation.backend.push.PushResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmRestartIntegrationTest {
    @TempDir Path directory;

    @Test
    void pendingReservationsSurviveTwoRealContextRestartsAndSentOnesDoNotRepeat() {
        UUID deviceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant now = AlarmApiIntegrationTest.NOW;
        try (var first = start(now)) {
            first.getBean(DeviceService.class).subscribe(AlarmApiIntegrationTest.subscription(deviceId));
            first.getBean(AlarmService.class).schedule(deviceId, sessionId, new ScheduleRequest(now.plusSeconds(1200), 5));
        }
        try (var second = start(now.plusSeconds(840))) {
            assertThat(second.getBean(AlarmRepository.class).count()).isEqualTo(2);
            runScheduler(second);
            assertThat(second.getBean(AlarmService.class).list(deviceId, sessionId))
                    .extracting(AlarmResponse::status).containsExactly(AlarmStatus.SENT, AlarmStatus.PENDING);
            verify(second.getBean(PushGateway.class), times(1)).send(any(), any(), anyInt());
        }
        try (var third = start(now.plusSeconds(850))) {
            runScheduler(third);
            verify(third.getBean(PushGateway.class), never()).send(any(), any(), anyInt());
            assertThat(third.getBean(AlarmRepository.class).count()).isEqualTo(2);
        }
    }

    private ConfigurableApplicationContext start(Instant now) {
        String url = "jdbc:h2:file:" + directory.resolve("nextstation").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        return new SpringApplicationBuilder(NextStationBackendApplication.class, RestartConfiguration.class)
                .web(WebApplicationType.NONE).run("--spring.datasource.url=" + url,
                        "--app.alarm.scheduler-enabled=false", "--app.push.enabled=false",
                        "--test.clock.instant=" + now, "--spring.main.banner-mode=off");
    }

    private void runScheduler(ConfigurableApplicationContext context) {
        new AlarmScheduler(context.getBean(AlarmRepository.class), context.getBean(AlarmDispatcher.class),
                context.getBean(PushGateway.class), context.getBean(AlarmProperties.class), context.getBean(Clock.class))
                .dispatchDueAlarms();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartConfiguration {
        @Bean @Primary
        Clock restartClock(@Value("${test.clock.instant}") String now) {
            return Clock.fixed(Instant.parse(now), ZoneOffset.UTC);
        }

        @Bean @Primary
        PushGateway restartGateway() {
            PushGateway gateway = mock(PushGateway.class);
            when(gateway.enabled()).thenReturn(true);
            when(gateway.send(any(), any(), anyInt())).thenReturn(PushResult.fromStatus(201));
            return gateway;
        }
    }
}
