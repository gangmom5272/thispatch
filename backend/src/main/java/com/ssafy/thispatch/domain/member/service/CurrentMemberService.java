package com.ssafy.thispatch.domain.member.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CurrentMemberService {

	private final MemberRepository memberRepository;

	/**
	 * 인증된 principal로 회원을 조회한다. 비인증 요청은 호출 전에 구분한다.
	 * 회원 부재의 응답 및 상태별 인증 허용 여부는 후속 인증 API 정책에서 처리한다.
	 */
	public Optional<Member> find(MemberPrincipal principal) {
		return memberRepository.findById(principal.memberId());
	}
}
