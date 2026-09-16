package com.ssafy.thispatch.global.security.jwt;

/** HTTP 응답 매핑은 호출 측에서 결정한다. 라이브러리 예외의 토큰·claim 원문을 전달하지 않는다. */
public class TokenValidationException extends RuntimeException {

	public enum Reason {
		INVALID,
		EXPIRED
	}

	private final Reason reason;

	public TokenValidationException(Reason reason) {
		super(reason == Reason.EXPIRED ? "Token has expired" : "Token is invalid");
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
