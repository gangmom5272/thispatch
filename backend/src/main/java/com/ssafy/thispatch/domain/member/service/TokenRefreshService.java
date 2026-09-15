package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.MEMBER_INACTIVE;
import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.REFRESH_TOKEN_INVALID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.dto.TokenRefreshResponse;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TokenRefreshService {

	private final RefreshTokenService refreshTokens;
	private final JwtTokenProvider tokenProvider;

	@Transactional(readOnly = true)
	public TokenRefreshResponse refresh(String refreshToken) {
		Member member;
		try {
			member = refreshTokens.findValidatedMember(refreshToken)
				.orElseThrow(() -> new BusinessException(MEMBER_INACTIVE));
		} catch (TokenValidationException exception) {
			throw new BusinessException(REFRESH_TOKEN_INVALID);
		}
		if (!"ACTIVE".equals(member.getStatus())) {
			throw new BusinessException(MEMBER_INACTIVE);
		}

		// 현재 Refresh Token과 저장 만료 시각을 유지한다.
		return TokenRefreshResponse.success(tokenProvider.issueAccessToken(member.getMemberId()));
	}
}
