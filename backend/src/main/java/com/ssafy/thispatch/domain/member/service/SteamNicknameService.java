package com.ssafy.thispatch.domain.member.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.dto.SteamNicknameResponse;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.exception.MemberErrorCode;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class SteamNicknameService {

	private final CurrentMemberService currentMemberService;
	private final MemberRepository memberRepository;

	public SteamNicknameResponse setNickname(MemberPrincipal principal, String nickname) {
		Member member = requireActiveMember(principal);
		if (member.getNickname() != null) {
			throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_SET);
		}

		if (memberRepository.setNicknameIfUnset(principal.memberId(), nickname, Instant.now()) == 0) {
			// 조건부 UPDATE 이후 캐시가 비워져, 동시 설정 또는 회원 상태 변경을 DB에서 다시 판정한다.
			requireActiveMember(principal);
			throw new BusinessException(MemberErrorCode.NICKNAME_ALREADY_SET);
		}
		return SteamNicknameResponse.of(nickname);
	}

	private Member requireActiveMember(MemberPrincipal principal) {
		return currentMemberService.find(principal)
			.filter(member -> "ACTIVE".equals(member.getStatus()))
			.orElseThrow(() -> new BusinessException(SecurityErrorCode.UNAUTHORIZED));
	}
}
