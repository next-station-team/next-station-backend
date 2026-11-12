package com.nextstation.backend.push;

import com.nextstation.backend.config.PushProperties;
import com.nextstation.backend.device.Device;
import com.nextstation.backend.device.SubscriptionValidator;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.BigIntegers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebPushGatewayTest {
    PushProperties properties;
    HttpClient client;
    Device device;

    @BeforeEach
    void setup() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("ECDH", "BC");
        generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));
        var pair = generator.generateKeyPair();
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.encode((ECPublicKey) pair.getPublic()));
        String privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(BigIntegers.asUnsignedByteArray(32, ((ECPrivateKey) pair.getPrivate()).getD()));
        properties = new PushProperties();
        properties.setEnabled(true);
        properties.setPublicKey(publicKey);
        properties.setPrivateKey(privateKey);
        properties.setSubject("mailto:push@example.com");
        client = mock(HttpClient.class);
        device = new Device(UUID.randomUUID(), "https://fcm.googleapis.com/wp/test", publicKey,
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]), Instant.now());
    }

    WebPushGateway gateway() {
        return new WebPushGateway(properties, new SubscriptionValidator(properties), new JsonMapper(), client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void encryptsAndSignsARealWebPushRequest() throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(201);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(gateway().send(device, PushPayload.test(Instant.now()), 42).outcome()).isEqualTo(PushResult.Outcome.ACCEPTED);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().firstValue("Content-Encoding")).contains("aes128gcm");
        assertThat(request.getValue().headers().firstValue("Authorization").orElseThrow()).startsWith("vapid t=");
        assertThat(request.getValue().headers().firstValue("TTL")).contains("42");
        assertThat(request.getValue().headers().firstValue("Urgency")).contains("high");
        assertThat(request.getValue().timeout()).contains(properties.getTimeout());
        assertThat(request.getValue().bodyPublisher().orElseThrow().contentLength()).isGreaterThan(100);
    }

    @Test
    @SuppressWarnings("unchecked")
    void networkTimeoutIsRetryable() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException());
        assertThat(gateway().send(device, PushPayload.test(Instant.now()), 60).outcome()).isEqualTo(PushResult.Outcome.RETRYABLE);
    }

    @Test
    void invalidVapidConfigurationFailsStartupWithoutLeakingKey() {
        properties.setPrivateKey("private-material-must-not-leak");
        assertThatThrownBy(this::gateway).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("private-material-must-not-leak").hasNoCause();
    }
}
