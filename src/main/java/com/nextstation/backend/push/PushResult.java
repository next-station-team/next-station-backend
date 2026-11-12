package com.nextstation.backend.push;

public record PushResult(Outcome outcome, String code) {
    public enum Outcome { ACCEPTED, EXPIRED_SUBSCRIPTION, RETRYABLE, FAILED }

    public static PushResult fromStatus(int status) {
        if (status >= 200 && status < 300) return new PushResult(Outcome.ACCEPTED, null);
        if (status == 404 || status == 410) return new PushResult(Outcome.EXPIRED_SUBSCRIPTION, "SUBSCRIPTION_EXPIRED");
        if (status == 408 || status == 429 || status >= 500) return new PushResult(Outcome.RETRYABLE, "PUSH_HTTP_" + status);
        return new PushResult(Outcome.FAILED, "PUSH_HTTP_" + status);
    }
}
