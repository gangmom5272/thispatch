package com.ssafy.thispatch.support;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ssafy.thispatch.domain.member.service.MemberAccessService;

/** MVC 단위 테스트의 기본 인증 회원. 실제 DB 상태 연동은 통합 테스트에서 검증한다. */
public abstract class ActiveMemberWebMvcTest {

	@MockitoBean
	protected MemberAccessService memberAccessService;

	@BeforeEach
	void allowActiveMemberFixture() {
		when(memberAccessService.isActive(anyLong())).thenReturn(true);
	}
}
