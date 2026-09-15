package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.MemberPrincipal;

class CurrentMemberServiceTest {

	private final MemberRepository repository = mock(MemberRepository.class);
	private final CurrentMemberService service = new CurrentMemberService(repository);

	@Test
	void findsMemberUsingPrincipalIdWithoutImposingStatusPolicy() {
		Member member = Member.builder().status("TEST_VALUE").build();
		when(repository.findById(42L)).thenReturn(Optional.of(member));

		assertThat(service.find(new MemberPrincipal(42L))).containsSame(member);
		verify(repository).findById(42L);
	}

	@Test
	void returnsEmptyWhenPrincipalMemberDoesNotExist() {
		when(repository.findById(42L)).thenReturn(Optional.empty());

		assertThat(service.find(new MemberPrincipal(42L))).isEmpty();
		verify(repository).findById(42L);
	}
}
