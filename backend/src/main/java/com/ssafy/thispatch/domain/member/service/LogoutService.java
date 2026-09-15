package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.LOGOUT_TOKEN_INVALID;

import org.springframework.stereotype.Service;

import com.ssafy.thispatch.domain.member.dto.LogoutResponse;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LogoutService {

	private final RefreshTokenService refreshTokens;

	public LogoutResponse logout(MemberPrincipal principal, String refreshToken) {
		try {
			// 폐기 트랜잭션과 소유자·현재 해시 확인은 기존 서비스에서 처리한다.
			refreshTokens.revoke(principal.memberId(), refreshToken);
		} catch (TokenValidationException exception) {
			throw new BusinessException(LOGOUT_TOKEN_INVALID);
		}
		return LogoutResponse.successResponse();
	}
}
