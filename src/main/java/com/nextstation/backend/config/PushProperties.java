package com.nextstation.backend.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("app.push")
public class PushProperties {
    private boolean enabled;
    private String publicKey = "";
    private String privateKey = "";
    private String subject = "";
    @NotNull
    private Duration timeout = Duration.ofSeconds(5);
    @NotEmpty
    private List<String> allowedHosts = List.of("fcm.googleapis.com", "web.push.apple.com",
            "updates.push.services.mozilla.com");
}
