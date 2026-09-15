package com.ssafy.thispatch.domain.member.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException.Reason;
import com.ssafy.thispatch.global.security.jwt.VerifiedToken;

import lombok.RequiredArgsConstructor;

/** 회원당 현재 Refresh Token 하나를 관리한다. 회원 상태별 허용 여부와 HTTP 오류 매핑은 호출 API의 책임이다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RefreshTokenService {

	private final MemberRepository memberRepository;
	private final JwtTokenProvider tokenProvider;

	/** 발급된 토큰을 저장하고 기존 토큰을 대체한다. 회원 생성과 같은 트랜잭션에서 호출할 수 있다. */
	@Transactional
	public void store(long memberId, String refreshToken) {
		VerifiedToken verified = tokenProvider.validateRefreshToken(refreshToken);
		requireOwner(verified, memberId);
		if (memberRepository.updateRefreshToken(memberId, hash(refreshToken), verified.expiresAt()) != 1) {
			throw new TokenValidationException(Reason.INVALID);
		}
	}

	/** JWT와 현재 저장 상태를 검증하고 상태 확인에 사용할 회원을 반환한다. 토큰을 교체하지 않는다. */
	public Member validate(String refreshToken) {
		VerifiedToken verified = tokenProvider.validateRefreshToken(refreshToken);
		Member member = memberRepository.findById(verified.memberId())
			.orElseThrow(() -> new TokenValidationException(Reason.INVALID));
		if (!hash(refreshToken).equals(member.getRefreshTokenHash())
			|| !verified.expiresAt().equals(member.getRefreshTokenExpiresAt())) {
			throw new TokenValidationException(Reason.INVALID);
		}
		return member;
	}

	/** 인증된 현재 회원 ID를 받는다. 현재 토큰과 일치할 때만 지우며 과거 토큰으로 새 토큰을 지우지 않는다. */
	@Transactional
	public void revoke(long memberId, String refreshToken) {
		VerifiedToken verified = tokenProvider.validateRefreshToken(refreshToken);
		requireOwner(verified, memberId);
		// 폐기 이력을 보관하지 않는다. 정상 JWT의 소유자가 같으면 이미 없거나 교체된 경우에도 성공한다.
		memberRepository.clearRefreshToken(memberId, hash(refreshToken));
	}

	private void requireOwner(VerifiedToken verified, long memberId) {
		if (verified.memberId() != memberId) {
			throw new TokenValidationException(Reason.INVALID);
		}
	}

	private String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
