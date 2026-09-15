package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.SteamLoginCodeErrorCode.STEAM_LOGIN_CODE_INVALID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.dto.SteamTokenResponse;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SteamTokenService {

	private final SteamLoginCodeService loginCodes;
	private final MemberRepository memberRepository;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenService refreshTokens;

	@Transactional
	public SteamTokenResponse exchange(String loginCode) {
		// Redis 소비는 DB 롤백과 독립적이다. 이후 실패해도 코드를 복구하지 않는다.
		long memberId = loginCodes.consume(loginCode);
		Member member = memberRepository.findById(memberId)
			.filter(value -> value.getLoginType() == LoginType.STEAM && "ACTIVE".equals(value.getStatus()))
			.orElseThrow(() -> new BusinessException(STEAM_LOGIN_CODE_INVALID));

		String accessToken = tokenProvider.issueAccessToken(memberId);
		String refreshToken = tokenProvider.issueRefreshToken(memberId);
		refreshTokens.store(memberId, refreshToken);
		return SteamTokenResponse.success(accessToken, refreshToken, member.getNickname());
	}
}
