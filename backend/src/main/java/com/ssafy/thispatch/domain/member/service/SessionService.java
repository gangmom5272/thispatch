package com.ssafy.thispatch.domain.member.service;

import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.ssafy.thispatch.domain.member.dto.SessionResponse;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.exception.MemberErrorCode;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SessionService {

	private final CurrentMemberService currentMemberService;

	public SessionResponse getSession(MemberPrincipal principal) {
		if (principal == null) {
			return SessionResponse.anonymous();
		}

		Optional<Member> member;
		try {
			// 조회 트랜잭션의 시작·종료 실패도 세션 조회 불가 응답으로 연결한다.
			member = currentMemberService.find(principal);
		} catch (DataAccessException | TransactionException exception) {
			throw new BusinessException(MemberErrorCode.SESSION_UNAVAILABLE, exception);
		}
		return member.filter(value -> "ACTIVE".equals(value.getStatus()))
			.map(SessionResponse::authenticated)
			.orElseGet(SessionResponse::anonymous);
	}
}
