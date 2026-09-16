package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.exception.MemberErrorCode;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorCode;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({SteamNicknameService.class, CurrentMemberService.class, SessionService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SteamNicknameServiceIntegrationTest {

	@Autowired
	private MemberRepository repository;
	@Autowired
	private SteamNicknameService service;
	@Autowired
	private SessionService sessionService;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final List<Long> memberIds = new ArrayList<>();
	private TransactionTemplate transaction;

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		transaction = new TransactionTemplate(transactionManager);
	}

	@AfterEach
	void deleteOnlyMembersCreatedByThisTest() {
		repository.deleteAllById(memberIds);
	}

	@ParameterizedTest
	@EnumSource(LoginType.class)
	void savesExactNicknameAndSessionWithoutChangingOtherFieldsOrTokens(LoginType loginType) {
		long memberId = newMember(loginType, "ACTIVE", null);
		Instant expiry = Instant.parse("2026-09-22T00:00:00Z");
		transaction.executeWithoutResult(status -> repository.updateRefreshToken(memberId, "a".repeat(64), expiry));
		Member before = repository.findById(memberId).orElseThrow();
		String nickname = "  한글 ! 🎮  ";

		assertThat(service.setNickname(new MemberPrincipal(memberId), nickname).data().nickname()).isEqualTo(nickname);

		Member after = repository.findById(memberId).orElseThrow();
		assertThat(after.getNickname()).isEqualTo(nickname);
		assertThat(after.getUpdatedAt()).isAfter(before.getCreatedAt());
		assertThat(after).usingRecursiveComparison().ignoringFields("nickname", "updatedAt").isEqualTo(before);
		var session = sessionService.getSession(new MemberPrincipal(memberId));
		assertThat(session.data().authenticated()).isTrue();
		assertThat(session.data().user().nickname()).isEqualTo(nickname);
	}

	@Test
	void differentMembersCanUseTheSameFiftyCodePointNickname() {
		String nickname = "🎮".repeat(50);
		long first = newMember(LoginType.STEAM, "ACTIVE", null);
		long second = newMember(LoginType.STEAM, "ACTIVE", null);
		service.setNickname(new MemberPrincipal(first), nickname);
		service.setNickname(new MemberPrincipal(second), nickname);
		assertThat(repository.findById(first).orElseThrow().getNickname()).isEqualTo(nickname);
		assertThat(repository.findById(second).orElseThrow().getNickname()).isEqualTo(nickname);
	}

	@Test
	void repeatedSettingKeepsOriginalNicknameAndTimestamp() {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", null);
		service.setNickname(new MemberPrincipal(memberId), "first");
		Member before = repository.findById(memberId).orElseThrow();

		assertThatThrownBy(() -> service.setNickname(new MemberPrincipal(memberId), "second"))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(MemberErrorCode.NICKNAME_ALREADY_SET));
		assertThat(repository.findById(memberId).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
	}

	@Test
	void rollbackDoesNotLeaveNicknameOrUpdatedTimestamp() {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", null);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			service.setNickname(new MemberPrincipal(memberId), "rolled-back");
			throw new IllegalStateException("test rollback");
		})).isInstanceOf(IllegalStateException.class);
		Member member = repository.findById(memberId).orElseThrow();
		assertThat(member.getNickname()).isNull();
		assertThat(member.getUpdatedAt()).isNull();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void stateChangeAfterInitialReadIsCheckedAgainWhenConditionalUpdateFails(boolean deleted) {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", null);
		TransactionTemplate independent = new TransactionTemplate(transactionManager);
		independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			// 최초 조회 결과는 영속성 컨텍스트에 ACTIVE 상태로 남겨둔다.
			assertThat(repository.findById(memberId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
			independent.executeWithoutResult(inner -> {
				if (deleted) {
					repository.deleteById(memberId);
				} else {
					jdbc.update("update member set status = 'INACTIVE' where member_id = ?", memberId);
				}
			});
			service.setNickname(new MemberPrincipal(memberId), "new");
		})).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(SecurityErrorCode.UNAUTHORIZED));

		if (deleted) {
			assertThat(repository.findById(memberId)).isEmpty();
		} else {
			Member member = repository.findById(memberId).orElseThrow();
			assertThat(member.getStatus()).isEqualTo("INACTIVE");
			assertThat(member.getNickname()).isNull();
			assertThat(member.getUpdatedAt()).isNull();
		}
	}

	@Test
	void concurrentRequestsThatAllReadNullHaveExactlyOneWinner() throws Exception {
		long memberId = newMember(LoginType.STEAM, "ACTIVE", null);
		int requestCount = 4;
		CyclicBarrier allReadNull = new CyclicBarrier(requestCount);
		var executor = Executors.newFixedThreadPool(requestCount);
		List<Future<Attempt>> futures = new ArrayList<>();
		try {
			for (int index = 0; index < requestCount; index++) {
				String nickname = "candidate-" + index;
				futures.add(executor.submit(() -> {
					try {
						return transaction.execute(status -> {
							assertThat(repository.findById(memberId).orElseThrow().getNickname()).isNull();
							await(allReadNull);
							service.setNickname(new MemberPrincipal(memberId), nickname);
							return new Attempt(nickname, null);
						});
					} catch (BusinessException error) {
						return new Attempt(nickname, error.getErrorCode().getCode());
					}
				}));
			}
			List<Attempt> attempts = new ArrayList<>();
			for (Future<Attempt> future : futures) {
				attempts.add(future.get(30, TimeUnit.SECONDS));
			}
			var successes = attempts.stream().filter(attempt -> attempt.errorCode() == null).toList();
			assertThat(successes).hasSize(1);
			assertThat(attempts.stream().filter(attempt -> attempt.errorCode() != null).toList())
				.hasSize(requestCount - 1).allSatisfy(attempt ->
					assertThat(attempt.errorCode()).isEqualTo("NICKNAME_ALREADY_SET"));
			assertThat(repository.findById(memberId).orElseThrow().getNickname()).isEqualTo(successes.get(0).nickname());
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private long newMember(LoginType loginType, String memberStatus, String nickname) {
		Member member = repository.saveAndFlush(Member.builder().loginType(loginType)
			.steamId(loginType == LoginType.STEAM ? BigInteger.valueOf(System.nanoTime()) : null)
			.status(memberStatus).nickname(nickname).createdAt(Instant.parse("2026-09-14T00:00:00Z")).build());
		memberIds.add(member.getMemberId());
		return member.getMemberId();
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(error);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private record Attempt(String nickname, String errorCode) {
	}
}
