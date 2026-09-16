package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.MEMBER_INACTIVE;
import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.REFRESH_TOKEN_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.ErrorCode;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JwtConfig.class, RefreshTokenService.class, TokenRefreshService.class})
class TokenRefreshServiceIntegrationTest {

	@Autowired
	private MemberRepository members;
	@Autowired
	private RefreshTokenService refreshTokens;
	@Autowired
	private TokenRefreshService service;
	@Autowired
	private JwtTokenProvider tokens;
	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@Test
	void repeatedRefreshIssuesNewAccessTokensWithoutChangingStoredMember() {
		long id = newMember("ACTIVE");
		String token = tokens.issueRefreshToken(id);
		refreshTokens.store(id, token);
		var before = jdbc.queryForMap("select * from member where member_id = ?", id);

		String first = service.refresh(token).data().accessToken();
		String second = service.refresh(token).data().accessToken();

		assertThat(first).isNotEqualTo(second);
		assertThat(tokens.validateAccessToken(first).memberId()).isEqualTo(id);
		assertThat(tokens.validateAccessToken(second).memberId()).isEqualTo(id);
		assertThat(jdbc.queryForMap("select * from member where member_id = ?", id)).isEqualTo(before);
		assertThat(refreshTokens.validate(token).getMemberId()).isEqualTo(id);
	}

	@Test
	void replacementAndRevocationPreventRefreshWhileCurrentTokenWorks() {
		long id = newMember("ACTIVE");
		String previous = tokens.issueRefreshToken(id);
		String current = tokens.issueRefreshToken(id);
		refreshTokens.store(id, previous);
		refreshTokens.store(id, current);

		assertError(previous, REFRESH_TOKEN_INVALID);
		assertThat(tokens.validateAccessToken(service.refresh(current).data().accessToken()).memberId()).isEqualTo(id);
		refreshTokens.revoke(id, current);
		assertError(current, REFRESH_TOKEN_INVALID);
		Member member = members.findById(id).orElseThrow();
		assertThat(member.getRefreshTokenHash()).isNull();
		assertThat(member.getRefreshTokenExpiresAt()).isNull();
	}

	@Test
	void tokenStoredForDifferentOwnerCannotRefreshEitherMember() {
		long ownerId = newMember("ACTIVE");
		long otherId = newMember("ACTIVE");
		String token = tokens.issueRefreshToken(ownerId);
		refreshTokens.store(ownerId, token);
		Member owner = refreshTokens.validate(token);
		members.updateRefreshToken(otherId, owner.getRefreshTokenHash(), owner.getRefreshTokenExpiresAt());
		members.clearRefreshToken(ownerId, owner.getRefreshTokenHash());
		var before = jdbc.queryForMap("select * from member where member_id = ?", otherId);

		assertError(token, REFRESH_TOKEN_INVALID);
		assertThat(jdbc.queryForMap("select * from member where member_id = ?", otherId)).isEqualTo(before);
	}

	@Test
	void missingAndInactiveMembersHaveMemberError() {
		long inactiveId = newMember("ACTIVE");
		String inactive = tokens.issueRefreshToken(inactiveId);
		refreshTokens.store(inactiveId, inactive);
		// 저장값이 남은 비활성 회원에 대한 기존 오류 계약을 검증한다.
		jdbc.update("update member set status = 'WITHDRAWN' where member_id = ?", inactiveId);
		assertError(inactive, MEMBER_INACTIVE);

		long missingId = newMember("ACTIVE");
		String missing = tokens.issueRefreshToken(missingId);
		refreshTokens.store(missingId, missing);
		members.deleteById(missingId);
		members.flush();
		assertError(missing, MEMBER_INACTIVE);
	}

	private long newMember(String status) {
		return members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL).status(status)
			.createdAt(Instant.now()).build()).getMemberId();
	}

	private void assertError(String token, ErrorCode expected) {
		assertThatThrownBy(() -> service.refresh(token)).isInstanceOf(BusinessException.class)
			.satisfies(error -> assertThat(((BusinessException)error).getErrorCode()).isEqualTo(expected));
	}
}
