package com.nextstation.backend;

import com.nextstation.backend.alarm.*;
import com.nextstation.backend.config.AlarmProperties;
import com.nextstation.backend.device.*;
import com.nextstation.backend.push.*;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AlarmApiIntegrationTest {
    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    static final String PUBLIC_KEY = publicKey();
    static final String AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DeviceService deviceService;
    @Autowired AlarmService service;
    @Autowired AlarmDispatcher dispatcher;
    @Autowired AlarmRepository alarms;
    @Autowired DeviceRepository devices;
    @Autowired AlarmProperties properties;
    @MockitoBean PushGateway gateway;
    @MockitoBean Clock clock;
    UUID deviceId;
    UUID sessionId;

    @BeforeEach
    void setup() {
        alarms.deleteAll();
        devices.deleteAll();
        when(clock.instant()).thenReturn(NOW);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.send(any(), any(), anyInt())).thenReturn(PushResult.fromStatus(201));
        deviceId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        deviceService.subscribe(subscription(deviceId));
    }

    static SubscriptionRequest subscription(UUID id) {
        return new SubscriptionRequest(id, "https://fcm.googleapis.com/wp/" + id,
                new SubscriptionRequest.Keys(PUBLIC_KEY, AUTH));
    }

    static String publicKey() {
        try {
            Security.addProvider(new BouncyCastleProvider());
            KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
            generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.encode((ECPublicKey) generator.generateKeyPair().getPublic()));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    String path() { return "/api/sessions/" + sessionId + "/alarms"; }

    @Test
    void subscriptionUpsertsWithoutExposingSecrets() throws Exception {
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        mvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(subscription(deviceId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.endpoint").doesNotExist()).andExpect(jsonPath("$.keys").doesNotExist());
        assertThat(devices.count()).isEqualTo(1);
    }

    @Test
    void rejectsEndpointTakeover() throws Exception {
        var original = subscription(deviceId);
        var duplicate = new SubscriptionRequest(UUID.randomUUID(), original.endpoint(), original.keys());
        mvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://fcm.googleapis.com/wp/abc", "https://localhost/secret", "https://127.0.0.1/secret",
            "https://fcm.googleapis.com.evil.example/wp/abc", "https://fcm.googleapis.com:8443/wp/abc",
            "https://user@fcm.googleapis.com/wp/abc", "https://fcm.googleapis.com/wp/abc#secret"})
    void rejectsUnsafeEndpoints(String endpoint) throws Exception {
        var request = new SubscriptionRequest(deviceId, endpoint, subscription(deviceId).keys());
        mvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PUSH_ENDPOINT"));
    }

    @Test
    void rejectsBadSubscriptionKeysAndMissingBodyFields() throws Exception {
        var invalid = new SubscriptionRequest(deviceId, subscription(deviceId).endpoint(), new SubscriptionRequest.Keys("bad", "bad"));
        mvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION_KEYS"));
        mvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 7})
    void schedulesBothStagesWithSafetyMargin(int minutes) throws Exception {
        mvc.perform(put(path()).header("X-Device-Id", deviceId).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ScheduleRequest(NOW.plusSeconds(1200), minutes))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("FINISH"))
                .andExpect(jsonPath("$[0].scheduledAt").value(NOW.plusSeconds(1200 - (minutes + 1) * 60L).toString()))
                .andExpect(jsonPath("$[1].scheduledAt").value(NOW.plusSeconds(1080).toString()));
    }

    @Test
    void defaultReminderIsFiveAndOffsetIsNormalizedToUtc() throws Exception {
        mvc.perform(put(path()).header("X-Device-Id", deviceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalAt\":\"2026-11-12T09:20:00+09:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].minutesBeforeArrival").value(5))
                .andExpect(jsonPath("$[0].scheduledAt").value("2026-11-12T00:14:00Z"));
    }

    @Test
    void correctionReplacesPendingIdsAndOldIdsCannotSend() {
        var old = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        var replacement = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1800), 7));
        assertThat(alarms.count()).isEqualTo(2);
        assertThat(replacement).extracting(AlarmResponse::alarmId).doesNotContainAnyElementsOf(old.stream().map(AlarmResponse::alarmId).toList());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1100));
        old.forEach(a -> dispatcher.dispatch(a.alarmId()));
        verify(gateway, never()).send(any(), any(), anyInt());
    }

    @Test
    void invalidCorrectionLeavesExistingReservationUntouched() throws Exception {
        var original = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        mvc.perform(put(path()).header("X-Device-Id", deviceId).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ScheduleRequest(NOW.minusSeconds(1), 5))))
                .andExpect(status().isBadRequest());
        assertThat(service.list(deviceId, sessionId)).isEqualTo(original);
    }

    @Test
    void validatesReminderTimeHeadersAndDevice() throws Exception {
        for (String body : List.of("{}", "{\"arrivalAt\":\"2026-11-12T00:10:00\"}",
                "{\"arrivalAt\":\"2026-11-12T00:20:00Z\",\"reminderMinutes\":4}",
                "{\"arrivalAt\":\"2026-11-14T00:20:00Z\"}")) {
            mvc.perform(put(path()).header("X-Device-Id", deviceId).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path())).andExpect(status().isBadRequest());
        mvc.perform(get(path()).header("X-Device-Id", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(put(path()).header("X-Device-Id", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ScheduleRequest(NOW.plusSeconds(1200), 5))))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellationIsIdempotentAndScopedToDevice() throws Exception {
        var original = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        UUID other = UUID.randomUUID();
        deviceService.subscribe(subscription(other));
        mvc.perform(delete(path()).header("X-Device-Id", other)).andExpect(status().isNoContent());
        assertThat(alarms.count()).isEqualTo(2);
        mvc.perform(get(path()).header("X-Device-Id", other)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete(path()).header("X-Device-Id", deviceId)).andExpect(status().isNoContent());
        mvc.perform(delete(path()).header("X-Device-Id", deviceId)).andExpect(status().isNoContent());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1100));
        original.forEach(a -> dispatcher.dispatch(a.alarmId()));
        verify(gateway, never()).send(any(), any(), anyInt());
        assertThat(alarms.count()).isZero();
    }

    @Test
    void schedulerSendsDueOnlyOnceAndPreservesSentStageDuringCorrection() {
        var scheduled = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        AlarmScheduler scheduler = new AlarmScheduler(alarms, dispatcher, gateway, properties, clock);
        scheduler.dispatchDueAlarms();
        verify(gateway, never()).send(any(), any(), anyInt());
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        scheduler.dispatchDueAlarms();
        scheduler.dispatchDueAlarms();
        verify(gateway, times(1)).send(any(), argThat(p -> p.type().equals("FINISH")), eq(60));
        assertThat(alarms.findById(scheduled.get(0).alarmId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.SENT);
        var correction = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1800), 7));
        assertThat(correction.stream().filter(a -> a.type() == AlarmKind.FINISH).findFirst().orElseThrow().status()).isEqualTo(AlarmStatus.SENT);
    }

    @Test
    void shortTripSkipsFinishAndSendsPrepareImmediately() {
        var scheduled = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(30), 5));
        scheduled.forEach(a -> dispatcher.dispatch(a.alarmId()));
        verify(gateway, times(1)).send(any(), argThat(p -> p.type().equals("PREPARE")), eq(30));
        assertThat(service.list(deviceId, sessionId)).extracting(AlarmResponse::status)
                .containsExactlyInAnyOrder(AlarmStatus.EXPIRED, AlarmStatus.SENT);
    }

    @Test
    void missedFinishIsExpiredAndOnlyCurrentStageSends() {
        var scheduled = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        when(clock.instant()).thenReturn(NOW.plusSeconds(1100));
        scheduled.forEach(a -> dispatcher.dispatch(a.alarmId()));
        verify(gateway, times(1)).send(any(), argThat(p -> p.type().equals("PREPARE")), anyInt());
    }

    @Test
    void afterArrivalNothingSends() {
        var scheduled = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        when(clock.instant()).thenReturn(NOW.plusSeconds(1200));
        scheduled.forEach(a -> dispatcher.dispatch(a.alarmId()));
        verify(gateway, never()).send(any(), any(), anyInt());
        assertThat(service.list(deviceId, sessionId)).allMatch(a -> a.status() == AlarmStatus.EXPIRED);
    }

    @Test
    void transientFailuresRetryWithBackoffAndStopAfterThreeAttempts() {
        UUID id = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5)).get(0).alarmId();
        when(gateway.send(any(), any(), anyInt())).thenReturn(PushResult.fromStatus(503));
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        dispatcher.dispatch(id);
        dispatcher.dispatch(id);
        verify(gateway, times(1)).send(any(), any(), anyInt());
        when(clock.instant()).thenReturn(NOW.plusSeconds(850));
        dispatcher.dispatch(id);
        when(clock.instant()).thenReturn(NOW.plusSeconds(870));
        dispatcher.dispatch(id);
        dispatcher.dispatch(id);
        Alarm alarm = alarms.findById(id).orElseThrow();
        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.FAILED);
        assertThat(alarm.getAttemptCount()).isEqualTo(3);
        verify(gateway, times(3)).send(any(), any(), anyInt());
    }

    @Test
    void successfulRetryMarksSent() {
        UUID id = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5)).get(0).alarmId();
        when(gateway.send(any(), any(), anyInt())).thenReturn(PushResult.fromStatus(429), PushResult.fromStatus(201));
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        dispatcher.dispatch(id);
        when(clock.instant()).thenReturn(NOW.plusSeconds(850));
        dispatcher.dispatch(id);
        assertThat(alarms.findById(id).orElseThrow().getStatus()).isEqualTo(AlarmStatus.SENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void goneSubscriptionDisablesDeviceAndAllPendingAlarms(int status) {
        var scheduled = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        when(gateway.send(any(), any(), anyInt())).thenReturn(PushResult.fromStatus(status));
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        dispatcher.dispatch(scheduled.get(0).alarmId());
        assertThat(devices.findById(deviceId).orElseThrow().isActive()).isFalse();
        assertThat(service.list(deviceId, sessionId)).allMatch(a -> a.status() == AlarmStatus.FAILED);
        deviceService.subscribe(subscription(deviceId));
        assertThat(devices.findById(deviceId).orElseThrow().isActive()).isTrue();
    }

    @Test
    void permanentFailureDoesNotRetryOrDisableSubscription() {
        UUID id = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5)).get(0).alarmId();
        when(gateway.send(any(), any(), anyInt())).thenReturn(PushResult.fromStatus(403));
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        dispatcher.dispatch(id);
        dispatcher.dispatch(id);
        assertThat(alarms.findById(id).orElseThrow().getStatus()).isEqualTo(AlarmStatus.FAILED);
        assertThat(devices.findById(deviceId).orElseThrow().isActive()).isTrue();
        verify(gateway, times(1)).send(any(), any(), anyInt());
    }

    @Test
    void disabledPushKeepsPendingAndTestReturns503() throws Exception {
        var scheduled = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        when(gateway.enabled()).thenReturn(false);
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        dispatcher.dispatch(scheduled.get(0).alarmId());
        assertThat(alarms.findById(scheduled.get(0).alarmId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.PENDING);
        mvc.perform(post("/api/push/test").header("X-Device-Id", deviceId)).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/push/vapid-public-key")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void testPushWorksAndRateLimits() throws Exception {
        mvc.perform(post("/api/push/test").header("X-Device-Id", deviceId)).andExpect(status().isAccepted());
        mvc.perform(post("/api/push/test").header("X-Device-Id", deviceId)).andExpect(status().isTooManyRequests());
    }

    @Test
    void expiredTestPushPersistsDeactivationBeforeReturning410() throws Exception {
        when(gateway.send(any(), any(), anyInt())).thenReturn(PushResult.fromStatus(410));
        mvc.perform(post("/api/push/test").header("X-Device-Id", deviceId)).andExpect(status().isGone());
        assertThat(devices.findById(deviceId).orElseThrow().isActive()).isFalse();
    }

    @Test
    void healthAndCorsAreAvailable() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(options(path()).header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "X-Device-Id,Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options(path()).header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentDispatchersCannotSendTheSameAlarmTwice() throws Exception {
        UUID id = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5)).get(0).alarmId();
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.send(any(), any(), anyInt())).thenAnswer(invocation -> {
            sending.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test send timed out");
            return PushResult.fromStatus(201);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> dispatcher.dispatch(id));
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> dispatcher.dispatch(id));
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            verify(gateway, times(1)).send(any(), any(), anyInt());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationWaitsForInFlightSendAndPreventsLaterSends() throws Exception {
        var scheduled = service.schedule(deviceId, sessionId, new ScheduleRequest(NOW.plusSeconds(1200), 5));
        when(clock.instant()).thenReturn(NOW.plusSeconds(840));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.send(any(), any(), anyInt())).thenAnswer(invocation -> {
            sending.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test send timed out");
            return PushResult.fromStatus(201);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> dispatcher.dispatch(scheduled.get(0).alarmId()));
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            var cancellation = pool.submit(() -> service.cancel(deviceId, sessionId));
            assertThatThrownBy(() -> cancellation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            cancellation.get(5, TimeUnit.SECONDS);
            when(clock.instant()).thenReturn(NOW.plusSeconds(1100));
            scheduled.forEach(a -> dispatcher.dispatch(a.alarmId()));
            assertThat(alarms.count()).isZero();
            verify(gateway, times(1)).send(any(), any(), anyInt());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
