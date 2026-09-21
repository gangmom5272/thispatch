package com.ssafy.thispatch.domain.member.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.dto.NicknameChangeResponse;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class NicknameChangeService {

	private final MemberRepository memberRepository;

	public NicknameChangeResponse changeNickname(MemberPrincipal principal, String nickname) {
		// 인증 필터 검사 후 회원이 탈퇴하거나 삭제되어도 갱신하지 않는다.
		if (memberRepository.changeNicknameIfActive(principal.memberId(), nickname, Instant.now()) != 1) {
			throw new BusinessException(SecurityErrorCode.UNAUTHORIZED);
		}
		return NicknameChangeResponse.of(nickname);
	}
}
