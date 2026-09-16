package com.ssafy.thispatch.domain.member.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

/** 보호 API의 JWT 인증 후 현재 회원 상태를 확인한다. 상태를 캐시하지 않는다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberAccessService {

	private final MemberRepository memberRepository;

	public boolean isActive(long memberId) {
		return memberRepository.existsByMemberIdAndStatus(memberId, "ACTIVE");
	}
}
