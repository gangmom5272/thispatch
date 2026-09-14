package com.ssafy.dispatch.collector.client;

import java.io.IOException;

/** 응답을 받은 요청의 실패. 네트워크 오류는 HttpClient의 IOException으로 전달한다. */
public final class SteamReviewException extends IOException {

    public enum Kind {
        HTTP_ERROR,
        API_FAILURE,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final int httpStatus;
    private final String retryAfter;

    SteamReviewException(Kind kind, int httpStatus, String retryAfter, String message) {
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

    /** Retry-After 원문. 초 또는 HTTP 날짜일 수 있으며, 헤더가 없으면 null이다. */
    public String retryAfter() {
        return retryAfter;
    }
}
