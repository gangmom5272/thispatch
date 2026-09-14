package com.ssafy.thispatch.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.service.CurrentMemberService;
import com.ssafy.thispatch.global.config.AppConfig;
import com.ssafy.thispatch.global.security.MemberPrincipal;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AppConfig.class, CurrentMemberService.class})
class MemberRepositoryTest {

	@Autowired
	private MemberRepository repository;

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private CurrentMemberService currentMemberService;

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@Test
	void savesAndFindsLocalMemberByIdAndEmail() {
		String email = UUID.randomUUID() + "@example.com";
		Instant createdAt = Instant.parse("2026-09-14T01:02:03.123456Z");
		Instant updatedAt = createdAt.plusSeconds(60);
		Member saved = repository.saveAndFlush(Member.builder()
			.loginType(LoginType.LOCAL).email(email).password("a".repeat(64))
			.nickname("회원").status("ACTIVE").createdAt(createdAt).updatedAt(updatedAt).build());
		entityManager.clear();

		Member found = repository.findByEmail(email).orElseThrow();
		assertThat(found.getMemberId()).isPositive().isEqualTo(saved.getMemberId());
		assertThat(repository.findById(saved.getMemberId())).contains(found);
		assertThat(currentMemberService.find(new MemberPrincipal(saved.getMemberId()))).contains(found);
		assertThat(found.getLoginType()).isEqualTo(LoginType.LOCAL);
		assertThat(found.getEmail()).isEqualTo(email);
		assertThat(found.getPassword()).isEqualTo("a".repeat(64));
		assertThat(found.getSteamId()).isNull();
		assertThat(found.getNickname()).isEqualTo("회원");
		assertThat(found.getStatus()).isEqualTo("ACTIVE");
		assertThat(found.getCreatedAt()).isEqualTo(createdAt);
		assertThat(found.getUpdatedAt()).isEqualTo(updatedAt);
		assertThat(repository.existsByEmail(email)).isTrue();
	}

	@Test
	void preservesTwentyDigitSteamIdNullableFieldsAndUnspecifiedStatus() {
		BigInteger steamId = new BigInteger("99999999999999999999");
		Member saved = repository.saveAndFlush(Member.builder()
			.loginType(LoginType.STEAM).steamId(steamId).status("TEST_VALUE")
			.createdAt(Instant.parse("2026-09-14T01:02:03Z")).build());
		entityManager.clear();

		Member found = repository.findBySteamId(steamId).orElseThrow();
		assertThat(found.getMemberId()).isEqualTo(saved.getMemberId());
		assertThat(found.getSteamId()).isEqualTo(steamId);
		assertThat(found.getLoginType()).isEqualTo(LoginType.STEAM);
		assertThat(found.getEmail()).isNull();
		assertThat(found.getPassword()).isNull();
		assertThat(found.getNickname()).isNull();
		assertThat(found.getUpdatedAt()).isNull();
		assertThat(found.getStatus()).isEqualTo("TEST_VALUE");
	}

	@Test
	void verifiesPasswordAfterReadingHashFromCharColumn() {
		String rawPassword = "member-password";
		String hash = passwordEncoder.encode(rawPassword);
		Member saved = repository.saveAndFlush(Member.builder()
			.loginType(LoginType.LOCAL).email(UUID.randomUUID() + "@example.com").password(hash)
			.status("ACTIVE").createdAt(Instant.parse("2026-09-14T01:02:03Z")).build());
		entityManager.clear();

		Member found = repository.findById(saved.getMemberId()).orElseThrow();
		assertThat(found.getPassword()).isEqualTo(hash);
		assertThat(passwordEncoder.matches(rawPassword, found.getPassword())).isTrue();
		assertThat(passwordEncoder.matches("wrong-password", found.getPassword())).isFalse();
	}

	@Test
	void returnsEmptyForMissingMember() {
		String email = UUID.randomUUID() + "@example.com";
		assertThat(repository.findById(-1L)).isEmpty();
		assertThat(repository.findByEmail(email)).isEmpty();
		assertThat(repository.findBySteamId(BigInteger.valueOf(-1))).isEmpty();
		assertThat(repository.existsByEmail(email)).isFalse();
	}
}
