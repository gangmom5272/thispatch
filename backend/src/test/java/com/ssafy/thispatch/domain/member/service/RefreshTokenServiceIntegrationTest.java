package com.ssafy.thispatch.domain.member.service;

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
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException;
import com.ssafy.thispatch.global.security.jwt.TokenValidationException.Reason;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JwtConfig.class, RefreshTokenService.class})
class RefreshTokenServiceIntegrationTest {

	@Autowired
	private MemberRepository repository;

	@Autowired
	private RefreshTokenService service;

	@Autowired
	private JwtTokenProvider provider;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@Test
	void storesValidatesAndRevokesWithoutRetainingHistory() {
		long memberId = newMember("TEST_VALUE");
		String token = provider.issueRefreshToken(memberId);
		service.store(memberId, token);

		Member found = service.validate(token);
		assertThat(found.getMemberId()).isEqualTo(memberId);
		assertThat(found.getStatus()).isEqualTo("TEST_VALUE");
		assertThat(found.getRefreshTokenHash()).matches("[0-9a-f]{64}").isNotEqualTo(token);
		assertThat(found.getRefreshTokenExpiresAt()).isEqualTo(provider.validateRefreshToken(token).expiresAt());
		// 갱신 검증 자체는 현재 토큰을 바꾸거나 소모하지 않는다.
		assertThat(service.validate(token).getRefreshTokenHash()).isEqualTo(found.getRefreshTokenHash());

		service.revoke(memberId, token);
		service.revoke(memberId, token);
		Member revoked = repository.findById(memberId).orElseThrow();
		assertThat(revoked.getRefreshTokenHash()).isNull();
		assertThat(revoked.getRefreshTokenExpiresAt()).isNull();
		assertInvalid(() -> service.validate(token));
	}

	@Test
	void replacementRejectsOldTokenAndOldLogoutPreservesNewToken() {
		long memberId = newMember("ACTIVE");
		String oldToken = provider.issueRefreshToken(memberId);
		String newToken = provider.issueRefreshToken(memberId);
		service.store(memberId, oldToken);
		service.store(memberId, newToken);

		assertInvalid(() -> service.validate(oldToken));
		service.revoke(memberId, oldToken);
		assertThat(service.validate(newToken).getMemberId()).isEqualTo(memberId);
		assertThat(jdbc.queryForObject("select count(*) from member where member_id = ? and refresh_token_hash is not null",
			Integer.class, memberId)).isEqualTo(1);
	}

	@Test
	void checksOwnerAgainstStorageAndRejectsCrossMemberRevocation() {
		long ownerId = newMember("ACTIVE");
		long otherId = newMember("ACTIVE");
		String token = provider.issueRefreshToken(ownerId);
		service.store(ownerId, token);
		assertInvalid(() -> service.revoke(otherId, token));
		Member owner = service.validate(token);

		// 다른 회원 행에 같은 해시가 있어도 JWT의 회원과 연결된 저장 상태가 없으면 사용할 수 없다.
		repository.updateRefreshToken(otherId, owner.getRefreshTokenHash(), owner.getRefreshTokenExpiresAt());
		repository.clearRefreshToken(ownerId, owner.getRefreshTokenHash());
		assertInvalid(() -> service.validate(token));
	}

	@Test
	void unknownTokenCannotRefreshOrClearTheCurrentToken() {
		long memberId = newMember("ACTIVE");
		String currentToken = provider.issueRefreshToken(memberId);
		String neverStored = provider.issueRefreshToken(memberId);
		service.store(memberId, currentToken);

		assertInvalid(() -> service.validate(neverStored));
		// 이력 없이 처리하므로 정상 JWT의 동일 소유자가 제출한 미저장 토큰도 폐기는 no-op이다.
		service.revoke(memberId, neverStored);
		assertThat(service.validate(currentToken).getMemberId()).isEqualTo(memberId);
	}

	@Test
	void rejectsStoredExpirationMismatch() {
		long memberId = newMember("ACTIVE");
		String token = provider.issueRefreshToken(memberId);
		service.store(memberId, token);
		Member member = service.validate(token);
		repository.updateRefreshToken(memberId, member.getRefreshTokenHash(), Instant.EPOCH);
		assertInvalid(() -> service.validate(token));
	}

	private long newMember(String status) {
		return repository.save(Member.builder().loginType(LoginType.LOCAL).status(status)
			.createdAt(Instant.now()).build()).getMemberId();
	}

	private void assertInvalid(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(TokenValidationException.class)
			.satisfies(exception -> assertThat(((TokenValidationException)exception).getReason()).isEqualTo(Reason.INVALID));
	}
}
