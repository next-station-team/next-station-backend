package com.nextstation.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("app.alarm")
public class AlarmProperties {
    @NotNull
    private Duration safetyMargin = Duration.ofMinutes(1);
    @Min(1) @Max(10)
    private int maxAttempts = 3;
    @NotNull
    private Duration retryDelay = Duration.ofSeconds(10);
    @Min(1) @Max(1000)
    private int batchSize = 100;

    @AssertTrue(message = "safetyMargin must be non-negative; retryDelay must be positive")
    public boolean isTimingValid() {
        return safetyMargin != null && !safetyMargin.isNegative()
                && retryDelay != null && !retryDelay.isNegative() && !retryDelay.isZero();
    }
}
