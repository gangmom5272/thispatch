package com.ssafy.thispatch.domain.member.service;

import java.math.BigInteger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SteamMemberService {

	private final MemberRepository memberRepository;

	// 별도 프록시의 트랜잭션이 반환 전에 커밋되어야 Redis 코드가 미커밋 회원을 가리키지 않는다.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Member findOrCreate(BigInteger steamId) {
		return memberRepository.findBySteamId(steamId).orElseGet(() -> {
			// UNIQUE 충돌만 무시한다. 다른 DB 오류는 그대로 전파하며, 기존 회원 정보는 덮어쓰지 않는다.
			memberRepository.insertSteamMemberIfAbsent(steamId);
			return memberRepository.findBySteamId(steamId).orElseThrow(
				() -> new IllegalStateException("Steam member was not found after insert"));
		});
	}
}
