package com.ssafy.thispatch.domain.member.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException.Reason;
import com.ssafy.thispatch.global.security.jwt.VerifiedToken;

import lombok.RequiredArgsConstructor;

/** ACTIVE 회원만 토큰을 저장한다. 검증 결과의 회원 상태와 HTTP 오류 매핑은 호출 API의 책임이다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RefreshTokenService {

	private final MemberRepository memberRepository;
	private final JwtTokenProvider tokenProvider;

	/** ACTIVE 조건으로 저장해 동시 탈퇴 후 토큰 복구를 막는다. 회원 생성 트랜잭션에도 참여한다. */
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
		return findValidatedMember(refreshToken)
			.orElseThrow(() -> new TokenValidationException(Reason.INVALID));
	}

	/** 회원 부재를 별도 오류로 처리해야 하는 API에 사용한다. 존재하는 회원의 저장 토큰은 반드시 검증한다. */
	public Optional<Member> findValidatedMember(String refreshToken) {
		VerifiedToken verified = tokenProvider.validateRefreshToken(refreshToken);
		Optional<Member> found = memberRepository.findById(verified.memberId());
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Member member = found.get();
		if (!hash(refreshToken).equals(member.getRefreshTokenHash())
			|| !verified.expiresAt().equals(member.getRefreshTokenExpiresAt())) {
			throw new TokenValidationException(Reason.INVALID);
		}
		return Optional.of(member);
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
