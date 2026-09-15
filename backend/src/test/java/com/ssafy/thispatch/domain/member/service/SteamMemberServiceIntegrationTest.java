package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;

import com.ssafy.thispatch.client.steam.SteamOpenIdClient;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.config.AppProperties;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(SteamMemberService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SteamMemberServiceIntegrationTest {

	@Autowired
	private SteamMemberService service;
	@Autowired
	private MemberRepository repository;
	@Autowired
	private JdbcTemplate jdbc;

	private BigInteger steamId;
	private final List<Long> localIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		steamId = new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16)
			.mod(new BigInteger("100000000000000000")).add(new BigInteger("76000000000000000"));
	}

	@AfterEach
	void cleanUpOnlyThisTestsMembers() {
		jdbc.update("delete from member where steam_id = ?", steamId);
		localIds.forEach(id -> jdbc.update("delete from member where member_id = ?", id));
	}

	@Test
	void createsNicknameLessMemberAndReusesItWithoutChangingFields() {
		Member first = service.findOrCreate(steamId);
		assertThat(first.getLoginType()).isEqualTo(LoginType.STEAM);
		assertThat(first.getStatus()).isEqualTo("ACTIVE");
		assertThat(first.getNickname()).isNull();
		assertThat(first.getEmail()).isNull();
		assertThat(first.getPassword()).isNull();
		assertThat(first.getUpdatedAt()).isNull();
		assertThat(first.getCreatedAt()).isNotNull();
		jdbc.update("update member set nickname = ?, status = ? where member_id = ?", "kept", "WITHDRAWN",
			first.getMemberId());
		Member second = service.findOrCreate(steamId);
		assertThat(second.getMemberId()).isEqualTo(first.getMemberId());
		assertThat(second.getNickname()).isEqualTo("kept");
		assertThat(second.getStatus()).isEqualTo("WITHDRAWN");
		assertThat(second.getCreatedAt()).isEqualTo(first.getCreatedAt());
	}

	@Test
	void concurrentCallbacksCreateExactlyOneMember() throws Exception {
		var executor = Executors.newFixedThreadPool(8);
		var ready = new CountDownLatch(8);
		var start = new CountDownLatch(1);
		try {
			var futures = new ArrayList<Future<Long>>();
			for (int index = 0; index < 8; index++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("concurrency start timed out");
					}
					return service.findOrCreate(steamId).getMemberId();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			var ids = new ArrayList<Long>();
			for (var future : futures) {
				ids.add(future.get(20, TimeUnit.SECONDS));
			}
			assertThat(ids).hasSize(8).containsOnly(ids.get(0));
			assertThat(jdbc.queryForObject("select count(*) from member where steam_id = ?", Integer.class, steamId))
				.isEqualTo(1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void uniqueConstraintAllowsMultipleNullsAndRejectsDuplicateSteamId() {
		for (int index = 0; index < 2; index++) {
			localIds.add(repository.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
				.status("ACTIVE").createdAt(Instant.now()).build()).getMemberId());
		}
		assertThat(localIds).hasSize(2);
		service.findOrCreate(steamId);
		assertThatThrownBy(() -> jdbc.update(
			"insert into member (login_type, steam_id, status, created_at) values ('STEAM', ?, 'ACTIVE', now())",
			steamId)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void commitsMemberBeforeCodeIssueAndKeepsMemberOnRedisFailure() {
		var client = mock(SteamOpenIdClient.class);
		var codes = mock(SteamLoginCodeService.class);
		when(client.verify(any())).thenReturn(Optional.of(steamId));
		when(codes.issue(anyLong())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(repository.findById(invocation.getArgument(0, Long.class))).isPresent();
			throw new IllegalStateException("Redis unavailable");
		});
		var callback = new SteamCallbackService(client, service, codes, new AppProperties(
			URI.create("https://frontend.example"), URI.create("https://backend.example"),
			new AppProperties.Cors(List.of(URI.create("https://frontend.example")))));
		assertThatThrownBy(() -> callback.callback(new LinkedMultiValueMap<>()))
			.isInstanceOf(IllegalStateException.class).hasMessage("Redis unavailable");
		assertThat(repository.findBySteamId(steamId)).isPresent();
		assertThat(jdbc.queryForObject("select count(*) from member where steam_id = ?", Integer.class, steamId))
			.isEqualTo(1);
	}
}
