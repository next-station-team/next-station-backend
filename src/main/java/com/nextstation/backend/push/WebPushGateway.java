package com.nextstation.backend.push;

import com.nextstation.backend.config.PushProperties;
import com.nextstation.backend.device.Device;
import com.nextstation.backend.device.SubscriptionValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Security;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Urgency;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebPushGateway implements PushGateway {
    private final PushProperties properties;
    private final SubscriptionValidator validator;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Encoder encoder;

    @Autowired
    public WebPushGateway(PushProperties properties, SubscriptionValidator validator, ObjectMapper mapper) {
        this(properties, validator, mapper, HttpClient.newBuilder().connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WebPushGateway(PushProperties properties, SubscriptionValidator validator, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.validator = validator;
        this.mapper = mapper;
        if (properties.getTimeout().isNegative() || properties.getTimeout().isZero()) {
            throw new IllegalArgumentException("app.push.timeout must be positive");
        }
        this.client = client;
        encoder = properties.isEnabled() ? createEncoder(properties) : null;
    }

    private static Encoder createEncoder(PushProperties properties) {
        try {
            if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
            URI subject = URI.create(properties.getSubject());
            if (!("mailto".equals(subject.getScheme()) && subject.getSchemeSpecificPart().contains("@"))
                    && !("https".equals(subject.getScheme()) && subject.getHost() != null)) {
                throw new IllegalArgumentException();
            }
            Encoder encoder = new Encoder(properties);
            if (!Utils.verifyKeyPair(encoder.getPrivateKey(), encoder.getPublicKey())) {
                throw new IllegalArgumentException();
            }
            return encoder;
        } catch (Exception ex) {
            // Never include the original exception: it may contain key material.
            throw new IllegalStateException("PUSH_ENABLED requires a valid VAPID key pair and mailto:/https: subject");
        }
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public PushResult send(Device device, PushPayload payload, int ttlSeconds) {
        if (!enabled()) return new PushResult(PushResult.Outcome.FAILED, "PUSH_DISABLED");
        try {
            validator.validateEndpoint(device.getEndpoint());
            Notification notification = Notification.builder().endpoint(device.getEndpoint())
                    .userPublicKey(device.getP256dh()).userAuth(device.getAuth())
                    .payload(mapper.writeValueAsBytes(payload)).ttl(Math.max(0, ttlSeconds))
                    .urgency(Urgency.HIGH).build();
            nl.martijndwars.webpush.HttpRequest encoded = encoder.encode(notification);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(encoded.getUrl()))
                    .timeout(properties.getTimeout()).POST(HttpRequest.BodyPublishers.ofByteArray(encoded.getBody()));
            encoded.getHeaders().forEach(builder::header);
            return PushResult.fromStatus(client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new PushResult(PushResult.Outcome.RETRYABLE, "PUSH_INTERRUPTED");
        } catch (IOException ex) {
            return new PushResult(PushResult.Outcome.RETRYABLE, "PUSH_NETWORK_ERROR");
        } catch (Exception ex) {
            return new PushResult(PushResult.Outcome.FAILED, "PUSH_ENCODING_ERROR");
        }
    }

    private static class Encoder extends AbstractPushService<Encoder> {
        Encoder(PushProperties properties) throws Exception {
            super(properties.getPublicKey(), properties.getPrivateKey(), properties.getSubject());
        }

        nl.martijndwars.webpush.HttpRequest encode(Notification notification) throws Exception {
            return prepareRequest(notification, Encoding.AES128GCM);
        }
    }
}
