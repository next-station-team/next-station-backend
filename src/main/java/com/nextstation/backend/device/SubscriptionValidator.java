package com.nextstation.backend.device;

import com.nextstation.backend.common.ApiException;
import com.nextstation.backend.config.PushProperties;
import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import nl.martijndwars.webpush.Utils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SubscriptionValidator {
    private final PushProperties properties;

    public void validate(SubscriptionRequest request) {
        validateEndpoint(request.endpoint());
        try {
            byte[] key = Base64.getUrlDecoder().decode(request.keys().p256dh());
            byte[] auth = Base64.getUrlDecoder().decode(request.keys().auth());
            if (key.length != 65 || key[0] != 4 || auth.length != 16) {
                throw new IllegalArgumentException();
            }
            Utils.loadPublicKey(request.keys().p256dh());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SUBSCRIPTION_KEYS",
                    "p256dh는 P-256 공개키, auth는 16바이트 Base64URL 값이어야 합니다.");
        }
    }

    public void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean trusted = properties.getAllowedHosts().stream().anyMatch(h ->
                    host.equals(h) || (h.equals("updates.push.services.mozilla.com") && host.endsWith("." + h)));
            if (!"https".equals(uri.getScheme()) || !trusted || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getPath() == null || uri.getPath().length() < 2) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_ENDPOINT",
                    "지원하는 푸시 제공자의 HTTPS 구독 endpoint가 필요합니다.");
        }
    }
}
