package com.ssafy.thispatch.collector.client;

import java.io.IOException;

/**
 * 응답을 받은 요청의 실패. 네트워크 오류는 HttpClient 의 IOException 으로 온다.
 *
 * <p>{@link SteamReviewException} 과 같은 모양이다. 재시도·백오프를 다루는 쪽에서
 * 두 수집기를 같은 방식으로 볼 수 있어야 해서 구조를 맞췄다.
 */
public final class SteamNewsException extends IOException {

    public enum Kind {
        HTTP_ERROR,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final int httpStatus;
    private final String retryAfter;

    SteamNewsException(Kind kind, int httpStatus, String retryAfter, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Retry-After 원문. 초 또는 HTTP 날짜일 수 있으며, 헤더가 없으면 null 이다. */
    public String retryAfter() {
        return retryAfter;
    }
}
