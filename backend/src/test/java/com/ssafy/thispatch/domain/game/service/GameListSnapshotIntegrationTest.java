package com.ssafy.thispatch.domain.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;
import com.ssafy.thispatch.domain.game.repository.GameListRepository;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;

@SpringBootTest
@ActiveProfiles("test")
class GameListSnapshotIntegrationTest {

	@Autowired private GameListService service;
	@MockitoSpyBean private GameListRepository repository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@Autowired private PlatformTransactionManager transactions;

	private long memberId;
	private long firstId;
	private String prefix;
	private boolean prepared;

	@BeforeEach
	void commitOnlyThisTestsFixtures() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		prefix = "snapshot-" + UUID.randomUUID();
		do {
			firstId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid between ? and ?", Integer.class,
			firstId, firstId + 2) != 0);
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			memberId = members.saveAndFlush(Member.builder().loginType(LoginType.LOCAL)
				.email(UUID.randomUUID() + "@example.com").status("ACTIVE").createdAt(Instant.now()).build()).getMemberId();
			for (long id : new long[] {firstId, firstId + 1}) {
				jdbc.update("insert into game (appid, name, store_positive_pct, collected_at) values (?, ?, 70, now())", id, prefix);
				jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", memberId, id);
			}
		});
		prepared = true;
	}

	@AfterEach
	void removeOnlyFixturesCreatedByThisTest() {
		if (prepared) {
			new TransactionTemplate(transactions).executeWithoutResult(status -> {
				jdbc.update("delete from my_game where member_id = ?", memberId);
				jdbc.update("delete from game where appid between ? and ? and name = ?", firstId, firstId + 2, prefix);
				jdbc.update("delete from member where member_id = ?", memberId);
			});
		}
	}

	@ParameterizedTest
	@EnumSource(GameListScope.class)
	void countItemsAndRegistrationUseOneSnapshotAndNextRequestSeesCommittedChanges(GameListScope scope) {
		var query = GameListQuery.of(prefix, GameListSort.POSITIVE_RATE_ASC, 10, null);
		var changed = new AtomicBoolean();
		doAnswer(invocation -> {
			Object count = invocation.callRealMethod();
			assertThat(jdbc.queryForObject("show transaction_isolation", String.class)).isEqualTo("repeatable read");
			assertThat(jdbc.queryForObject("show transaction_read_only", String.class)).isEqualTo("on");
			if (changed.compareAndSet(false, true)) {
				// count와 목록 SQL 사이에 별도 연결의 쓰기 트랜잭션을 커밋한다.
				var writer = new TransactionTemplate(transactions);
				writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
				writer.executeWithoutResult(status -> {
					jdbc.update("delete from my_game where member_id = ?", memberId);
					jdbc.update("delete from game where appid = ?", firstId + 1);
					jdbc.update("insert into game (appid, name, store_positive_pct, collected_at) values (?, ?, 70, now())",
						firstId + 2, prefix);
					jdbc.update("insert into my_game (member_id, appid, created_at) values (?, ?, now())", memberId, firstId + 2);
				});
			}
			return count;
		}).when(repository).count(anyLong(), any(), any());

		var first = service.getGames(memberId, query, null, scope).data();
		assertThat(first.page().totalCount()).isEqualTo(2);
		assertThat(first.items()).extracting(item -> item.id()).containsExactly(firstId, firstId + 1);
		assertThat(first.items()).allMatch(item -> item.isMine());
		var second = service.getGames(memberId, query, null, scope).data();
		if (scope == GameListScope.ALL) {
			assertThat(second.page().totalCount()).isEqualTo(2);
			assertThat(second.items()).extracting(item -> item.id()).containsExactly(firstId, firstId + 2);
			assertThat(second.items().get(0).isMine()).isFalse();
		} else {
			assertThat(second.page().totalCount()).isEqualTo(1);
			assertThat(second.items()).extracting(item -> item.id()).containsExactly(firstId + 2);
		}
	}
}