package com.ssafy.thispatch.global.security;

import java.security.Principal;

/** 검증된 Access Token의 회원 식별자. 토큰 원문이나 회원 개인정보를 보관하지 않는다. */
public record MemberPrincipal(long memberId) implements Principal {

	public MemberPrincipal {
		if (memberId <= 0) {
			throw new IllegalArgumentException("memberId must be positive");
		}
	}

	@Override
	public String getName() {
		return Long.toString(memberId);
	}
}
