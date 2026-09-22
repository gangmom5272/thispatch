package com.ssafy.thispatch.domain.patch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;
import com.ssafy.thispatch.domain.patch.config.PatchSearchProperties;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PatchSearchRepositoryIntegrationTest {
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PatchSearchRepository repository;
	@Autowired private PatchSearchProperties properties;
	private long gameId;
	private String gid;
	private long chunkId;
	private String model;
	private int genreId;
	private final ConfirmedSlot slot = new ConfirmedSlot(new Target("Axebot", TargetRole.ENEMY), "HP",
		ChangeType.MODIFY, Direction.INCREASE, "+20%", null);
	private final List<Double> vector = new ArrayList<>(Collections.nCopies(512, 0.0));

	@BeforeEach
	void prepareOnlyInTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		gameId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		gid = "test-" + gameId;
		model = "test-" + gameId;
		genreId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		vector.set(0, 1.0);
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'Search Game', now())", gameId);
		jdbc.update("insert into tag (tag_id, name_ko) values (?, 'test genre')", genreId);
		jdbc.update("insert into game_tag (appid, tag_id, weight) values (?, ?, 1)", gameId, genreId);
		announcement(gid + "a", "2026-01-01T00:00:00Z", true);
		announcement(gid + "b", "2026-01-11T00:00:00Z", true);
		announcement(gid, "2026-01-25T00:00:00Z", true);
		announcement(gid + "c", "2026-01-31T00:00:00Z", true);
		// 비패치 공지는 주기 계산과 검색 후보에 들어가면 안 된다.
		announcement(gid + "d", "2026-01-27T00:00:00Z", false);
		jdbc.update("""
			insert into patch_stat (gid, appid, patched_at, before_review_count, before_positive_pct,
			  after_review_count, after_positive_pct, delta_pct)
			values (?, ?, '2026-01-25T00:00:00Z', 10, 70, 20, 73, 99)
			""", gid, gameId);
		chunkId = chunk(gid, 1);
		change(chunkId, "modify", "increase", "valid");
	}

	@Test
	void findsDeduplicatedPatchAndUsesHistoricalMeanNotFutureIntervals() {
		change(chunk(gid, 2), "modify", "increase", "valid");
		var result = repository.search(List.of(vector, vector), model, List.of(slot, slot), List.of(genreId));
		assertThat(result).hasSize(1);
		var candidate = result.get(0);
		assertThat(candidate.patch().gid()).isEqualTo(gid);
		assertThat(candidate.patch().averageDays()).isEqualTo(12.0);
		assertThat(candidate.patch().nextDays()).isEqualTo(6.0);
		assertThat(candidate.patch().deltaPp()).isEqualByComparingTo("3");
		assertThat(candidate.patch().reviewCount()).isEqualTo(20);
		assertThat(candidate.genres()).extracting(PatchSearchRepository.Genre::id).containsExactly(genreId);
		assertThat(candidate.cosine()).isCloseTo(1, org.assertj.core.data.Offset.offset(.0001));
		assertThat(jdbc.queryForObject("SHOW hnsw.ef_search", Integer.class)).isEqualTo(properties.efSearch());
	}

	@Test
	void searchesWithCustomEfSearchAndKeepsStrictOrdering() {
		var customRepository = new PatchSearchRepository(new NamedParameterJdbcTemplate(jdbc),
			new PatchSearchProperties(60));
		assertThat(customRepository.search(List.of(vector), model, List.of(slot), List.of(genreId)))
			.extracting(candidate -> candidate.patch().gid()).containsExactly(gid);
		assertThat(jdbc.queryForObject("SHOW hnsw.ef_search", Integer.class)).isEqualTo(60);
		assertThat(jdbc.queryForObject("SHOW hnsw.iterative_scan", String.class)).isEqualTo("strict_order");
	}

	@ParameterizedTest
	@CsvSource({"40, false", "60, false", "40, true", "60, true"})
	void restoresSettingsOnSameConnectionAfterCommitOrRollback(int efSearch, boolean rollback) throws Exception {
		// 별도 연결을 고정해 다음 트랜잭션이 같은 PostgreSQL 세션을 재사용하는 경우를 검증한다.
		try (var connection = jdbc.getDataSource().getConnection()) {
			var dataSource = new SingleConnectionDataSource(connection, true);
			var isolatedJdbc = new JdbcTemplate(dataSource);
			assertThat(isolatedJdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
			// pgvector를 로드한 뒤 이 연결의 원래 설정을 확인한다.
			isolatedJdbc.queryForObject("select '[1,0]'::vector <=> '[1,0]'::vector", Double.class);
			String originalEfSearch = isolatedJdbc.queryForObject("SHOW hnsw.ef_search", String.class);
			String originalMode = isolatedJdbc.queryForObject("SHOW hnsw.iterative_scan", String.class);
			var customRepository = new PatchSearchRepository(new NamedParameterJdbcTemplate(isolatedJdbc),
				new PatchSearchProperties(efSearch));
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			transaction.setReadOnly(true);
			transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
			transaction.executeWithoutResult(status -> {
				assertThat(customRepository.search(List.of(vector), model + "-absent", List.of(slot), List.of())).isEmpty();
				assertThat(isolatedJdbc.queryForObject("SHOW hnsw.ef_search", Integer.class)).isEqualTo(efSearch);
				assertThat(isolatedJdbc.queryForObject("SHOW hnsw.iterative_scan", String.class)).isEqualTo("strict_order");
				if (rollback) status.setRollbackOnly();
			});
			transaction.executeWithoutResult(status -> {
				assertThat(isolatedJdbc.queryForObject("SHOW hnsw.ef_search", String.class)).isEqualTo(originalEfSearch);
				assertThat(isolatedJdbc.queryForObject("SHOW hnsw.iterative_scan", String.class)).isEqualTo(originalMode);
			});
		}
	}

	@Test
	void requiresTypeAndDirectionOnSameChangeAndExcludesRejectedChanges() {
		jdbc.update("delete from patch_change where chunk_id = ?", chunkId);
		change(chunkId, "modify", "decrease", "valid");
		change(chunkId, "add", "increase", "valid");
		assertThat(search()).isEmpty();
		change(chunkId, "modify", "increase", "rejected");
		assertThat(search()).isEmpty();
		change(chunkId, "modify", "increase", "needs_review");
		assertThat(search()).hasSize(1);
	}

	@Test
	void excludesNonPatchMissingStatsWrongModelAndGenre() {
		assertThat(repository.search(List.of(vector), "different-model", List.of(slot), List.of())).isEmpty();
		assertThat(repository.search(List.of(vector), model, List.of(slot), List.of(-1))).isEmpty();
		jdbc.update("update news set is_patch = false where gid = ?", gid);
		assertThat(search()).isEmpty();
		jdbc.update("update news set is_patch = true where gid = ?", gid);
		jdbc.update("update patch_stat set before_positive_pct = null where gid = ?", gid);
		assertThat(search()).isEmpty();
	}

	@Test
	void cyclesAreNullableWhenNoEarlierOrLaterPatchExists() {
		jdbc.update("update news set is_patch = false where appid = ? and gid <> ?", gameId, gid);
		var patch = search().get(0).patch();
		assertThat(patch.averageDays()).isNull();
		assertThat(patch.nextDays()).isNull();
	}

	@ParameterizedTest
	@CsvSource({"modify, decrease, valid", "add, increase, valid", "modify, increase, rejected"})
	void changeMismatchesDoNotConsumeThirtyCandidateLimit(String type, String direction, String status) {
		// 가까운 30개가 부적합해도 31번째의 적합한 변경점을 찾아야 한다.
		makeLessSimilar(chunkId);
		for (int sequence = 2; sequence <= 31; sequence++) {
			change(chunk(gid, sequence), type, direction, status);
		}
		assertThat(search()).extracting(candidate -> candidate.patch().gid()).containsExactly(gid);
	}

	@Test
	void keepsThirtyChunkLimitEvenWhenAllMatchesBelongToOnePatch() {
		String laterPatch = gid + "b";
		jdbc.update("""
			insert into patch_stat (gid, appid, patched_at, before_review_count, before_positive_pct,
			  after_review_count, after_positive_pct)
			values (?, ?, '2026-01-11T00:00:00Z', 10, 70, 20, 73)
			""", laterPatch, gameId);
		long laterChunk = chunk(laterPatch, 1);
		change(laterChunk, "modify", "increase", "valid");
		makeLessSimilar(laterChunk);
		for (int sequence = 2; sequence <= 30; sequence++) {
			change(chunk(gid, sequence), "modify", "increase", "valid");
		}
		// 결과 개수를 늘리려고 31번째 패치를 추가하지 않는다.
		assertThat(search()).extracting(candidate -> candidate.patch().gid()).containsExactly(gid);
	}

	@ParameterizedTest
	@CsvSource({"ADD, add", "REMOVE, remove", "FIX, fix", "DEPRECATE, deprecate"})
	void nonModifyChangesIgnoreDirection(ChangeType changeType, String code) {
		jdbc.update("delete from patch_change where chunk_id = ?", chunkId);
		change(chunkId, code, "none", "valid");
		var nonModifySlot = new ConfirmedSlot(slot.target(), slot.attribute(), changeType,
			Direction.NOT_APPLICABLE, null, null);
		assertThat(repository.search(List.of(vector), model, List.of(nonModifySlot), List.of()))
			.extracting(candidate -> candidate.patch().gid()).containsExactly(gid);
	}

	@Test
	void ineligiblePatchesDoNotConsumeThirtyCandidateLimit() {
		makeLessSimilar(chunkId);
		for (int sequence = 1; sequence <= 40; sequence++) {
			// 이 공지는 패치가 아니고 전후 리뷰 통계도 없다.
			change(chunk(gid + "d", sequence), "modify", "increase", "valid");
		}
		assertThat(search()).extracting(candidate -> candidate.patch().gid()).containsExactly(gid);
	}

	@ParameterizedTest
	@CsvSource({"false", "true"})
	@SuppressWarnings("unchecked")
	void usesHnswAndContinuesScanningPastFilteredOutNeighbors(boolean wrongDirection) {
		// 벡터 UPDATE 전에 생성해야 같은 테스트 트랜잭션에서도 인덱스를 사용할 수 있다.
		// UPDATE 후 생성하면 HOT 체인 검사(indcheckxmin)로 현재 스냅샷의 인덱스 사용이 제한된다.
		jdbc.execute("CREATE INDEX test_patch_search_hnsw ON patch_chunk USING hnsw (embedding vector_cosine_ops)");
		makeLessSimilar(chunkId);
		// 초기 ef_search(100)보다 가까운 이웃이 많아도 모델·방향 불일치를 넘어 탐색한다.
		jdbc.update("""
			insert into patch_chunk (gid, seq, text, extraction_status, embedding_status, embedding, embedding_model)
			select ?, sequence, 'Filtered neighbor', 'succeeded', 'succeeded', cast(? as vector), ?
			from generate_series(2, 161) sequence
			""", gid, "[1," + "0,".repeat(510) + "0]", wrongDirection ? model : "other-model");
		if (wrongDirection) {
			for (Long id : jdbc.queryForList("select chunk_id from patch_chunk where gid = ? and seq >= 2", Long.class, gid)) {
				change(id, "modify", "decrease", "valid");
			}
		}
		// 작은 fixture에서도 운영과 같은 인덱스 경로를 실행한다. DDL/설정은 테스트 종료 시 롤백된다.
		jdbc.execute("SET LOCAL enable_seqscan = off");
		jdbc.execute("SET LOCAL enable_sort = off");
		var recordedJdbc = spy(new NamedParameterJdbcTemplate(jdbc));
		var indexedRepository = new PatchSearchRepository(recordedJdbc, properties);
		var result = indexedRepository.search(List.of(vector), model, List.of(slot), List.of(genreId));
		assertThat(result).extracting(candidate -> candidate.patch().gid()).containsExactly(gid);

		var sql = ArgumentCaptor.forClass(String.class);
		var parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
		verify(recordedJdbc, atLeastOnce()).query(sql.capture(), parameters.capture(), any(RowMapper.class));
		// 최초 쿼리가 후보 검색이며, 이후 상세 정보 쿼리는 실행 계획 검증 대상이 아니다.
		String plan = recordedJdbc.queryForObject("EXPLAIN (FORMAT JSON) " + sql.getAllValues().get(0),
			parameters.getAllValues().get(0), String.class);
		assertThat(plan).contains("test_patch_search_hnsw");
		assertThat(jdbc.queryForObject("SHOW hnsw.iterative_scan", String.class)).isEqualTo("strict_order");
	}

	private void makeLessSimilar(long id) {
		jdbc.update("update patch_chunk set embedding = cast(? as vector) where chunk_id = ?",
			"[1,1," + "0,".repeat(509) + "0]", id);
	}

	private List<PatchSearchRepository.Candidate> search() {
		return repository.search(List.of(vector), model, List.of(slot), List.of());
	}

	private void announcement(String id, String date, boolean patch) {
		jdbc.update("""
			insert into news (gid, appid, title, contents, published_ts, is_patch, collected_at)
			values (?, ?, 'Patch', 'Increase enemy HP', ?, ?, now())
			""", id, gameId, OffsetDateTime.parse(date), patch);
	}

	private long chunk(String id, int seq) {
		String literal = "[1," + "0,".repeat(510) + "0]";
		return jdbc.queryForObject("""
			insert into patch_chunk (gid, seq, text, extraction_status, embedding_status, embedding, embedding_model)
			values (?, ?, 'Increase HP', 'succeeded', 'succeeded', cast(? as vector), ?) returning chunk_id
			""", Long.class, id, seq, literal, model);
	}

	private void change(long chunk, String type, String direction, String status) {
		jdbc.update("""
			insert into patch_change (chunk_id, change_type_id, direction_id, target_type_id, evidence_quote, validation_status)
			select ?, ct.change_type_id, d.direction_id, t.target_type_id, 'HP +20%', ?
			from patch_change_type ct cross join patch_change_direction d cross join patch_change_target_type t
			where ct.code = ? and d.code = ? and t.code = 'enemy'
			""", chunk, status, type, direction);
	}
}
