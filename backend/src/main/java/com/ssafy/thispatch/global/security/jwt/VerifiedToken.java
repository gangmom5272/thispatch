package com.ssafy.thispatch.global.security.jwt;

import java.time.Instant;

/** 서명·용도·필수 claim·만료 검증을 통과한 토큰 정보. 토큰 원문은 보관하지 않는다. */
public record VerifiedToken(long memberId, TokenType type, String tokenId, Instant issuedAt, Instant expiresAt) {
}
