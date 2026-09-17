package com.ssafy.thispatch.domain.patch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PatchSearchRepositoryIntegrationTest {
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PatchSearchRepository repository;
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
