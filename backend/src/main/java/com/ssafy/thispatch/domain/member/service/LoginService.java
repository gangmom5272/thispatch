package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.LOGIN_FAILED;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.dto.LoginResponse;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;

@Service
public class LoginService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenService refreshTokens;
	private final String dummyPasswordHash;

	public LoginService(MemberRepository memberRepository, PasswordEncoder passwordEncoder,
		JwtTokenProvider tokenProvider, RefreshTokenService refreshTokens) {
		this.memberRepository = memberRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenProvider = tokenProvider;
		this.refreshTokens = refreshTokens;
		this.dummyPasswordHash = passwordEncoder.encode("unused-login-password");
	}

	@Transactional
	public LoginResponse login(String email, String password) {
		Member member = memberRepository.findByEmail(email).orElse(null);
		String hash = member == null ? null : member.getPassword();
		// 회원 부재·비밀번호 미설정 시에도 BCrypt를 수행해 즉시 실패하는 경로를 피한다.
		boolean passwordMatches = passwordEncoder.matches(password, hash == null ? dummyPasswordHash : hash);
		if (member == null || member.getLoginType() != LoginType.LOCAL || !"ACTIVE".equals(member.getStatus())
			|| hash == null || !passwordMatches) {
			throw new BusinessException(LOGIN_FAILED);
		}

		long memberId = member.getMemberId();
		String accessToken = tokenProvider.issueAccessToken(memberId);
		String refreshToken = tokenProvider.issueRefreshToken(memberId);
		try {
			refreshTokens.store(memberId, refreshToken);
		} catch (TokenValidationException exception) {
			throw new BusinessException(LOGIN_FAILED);
		}
		return LoginResponse.success(accessToken, refreshToken);
	}
}
