package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.EMAIL_ALREADY_REGISTERED;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.dto.SignupResponse;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignupService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenService refreshTokens;

	@Transactional
	public SignupResponse signup(String email, String password, String nickname) {
		if (memberRepository.existsByEmail(email)) {
			throw new BusinessException(EMAIL_ALREADY_REGISTERED);
		}
		// 사전 조회를 함께 통과한 요청도 DB UNIQUE 제약으로 한 번만 생성한다.
		if (memberRepository.insertLocalMemberIfAbsent(email, passwordEncoder.encode(password), nickname) != 1) {
			throw new BusinessException(EMAIL_ALREADY_REGISTERED);
		}
		long memberId = memberRepository.findByEmail(email).orElseThrow(
			() -> new IllegalStateException("Local member was not found after insert")).getMemberId();
		String accessToken = tokenProvider.issueAccessToken(memberId);
		String refreshToken = tokenProvider.issueRefreshToken(memberId);
		refreshTokens.store(memberId, refreshToken);
		return SignupResponse.success(accessToken, refreshToken);
	}
}
