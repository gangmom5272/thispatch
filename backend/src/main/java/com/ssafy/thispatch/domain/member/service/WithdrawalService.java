package com.ssafy.thispatch.domain.member.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.dto.WithdrawalResponse;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class WithdrawalService {

	private final MemberRepository memberRepository;

	public WithdrawalResponse withdraw(MemberPrincipal principal) {
		// 필터 검사 후의 동시 탈퇴도 조건부 UPDATE로 판정한다. 상태와 토큰을 함께 변경한다.
		if (memberRepository.withdrawIfActive(principal.memberId(), Instant.now()) != 1) {
			throw new BusinessException(SecurityErrorCode.UNAUTHORIZED);
		}
		return WithdrawalResponse.successResponse();
	}
}
