package com.ssafy.thispatch.domain.patch.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.client.ai.AiPatchContracts.CaseChange;
import com.ssafy.thispatch.domain.patch.config.PatchSearchProperties;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class PatchSearchRepository {
	private static final int CANDIDATES_PER_SLOT = 30;
	private final NamedParameterJdbcTemplate jdbc;
	private final PatchSearchProperties properties;

	public Optional<GameContext> findGame(long gameId) {
		return jdbc.query("""
			select g.appid, g.name, t.tag_id, t.name_ko
			from game g left join game_tag gt on gt.appid = g.appid
			left join tag t on t.tag_id = gt.tag_id
			where g.appid = :gameId order by gt.weight desc, t.tag_id
			""", Map.of("gameId", gameId), rows -> {
			if (!rows.next()) return Optional.empty();
			String title = rows.getString("name");
			List<Genre> genres = new ArrayList<>();
			do {
				Integer id = rows.getObject("tag_id", Integer.class);
				if (id != null) genres.add(new Genre(id, rows.getString("name_ko")));
			} while (rows.next());
			return Optional.of(new GameContext(gameId, title, genres));
		});
	}

	public List<Genre> findGenres(List<Integer> ids) {
		if (ids.isEmpty()) return List.of();
		return jdbc.query("select tag_id, name_ko from tag where tag_id in (:ids) order by tag_id",
			Map.of("ids", ids), (row, index) -> new Genre(row.getInt("tag_id"), row.getString("name_ko")));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<Candidate> search(List<List<Double>> vectors, String model,
		List<ConfirmedSlot> slots, List<Integer> genreIds) {
		// 필터에서 탈락한 후보만큼 HNSW 탐색을 이어 간다. 설정은 이 트랜잭션에서만 유지한다.
		jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.iterative_scan = 'strict_order'");
		jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.ef_search = " + properties.efSearch());
		Map<String, Match> bestByPatch = new LinkedHashMap<>();
		List<Integer> slotMatchCounts = new ArrayList<>();
		for (int index = 0; index < slots.size(); index++) {
			var matches = nearest(vectors.get(index), model, slots.get(index), genreIds);
			slotMatchCounts.add(matches.size());
			for (Match match : matches) {
				bestByPatch.merge(match.gid(), match, (previous, current) ->
					current.cosine() > previous.cosine() ? current : previous);
			}
		}
		log.info("Case search candidates: slotMatchCounts={}, distinctPatchCount={}",
			slotMatchCounts, bestByPatch.size());
		if (bestByPatch.isEmpty()) return List.of();
		var patchIds = List.copyOf(bestByPatch.keySet());
		var patches = details(patchIds);
		var genres = genresByGame(patches.stream().map(PatchData::gameId).distinct().toList());
		var changes = changesByPatch(patchIds);
		List<Candidate> candidates = new ArrayList<>();
		for (PatchData patch : patches) {
			Match match = bestByPatch.get(patch.gid());
			// 매칭된 변경점이 비교 입력의 최대 200개 밖으로 밀리지 않도록 앞에 둔다.
			var orderedChanges = new ArrayList<>(changes.getOrDefault(patch.gid(), List.of()));
			orderedChanges.sort(java.util.Comparator.comparing(change -> change.chunkId() != match.chunkId()));
			if (orderedChanges.isEmpty()) continue;
			candidates.add(new Candidate(patch, match.cosine(), genres.getOrDefault(patch.gameId(), List.of()),
				orderedChanges.stream().limit(200).map(StoredChange::change).toList(), orderedChanges.size() > 200));
		}
		return candidates;
	}

	private List<Match> nearest(List<Double> vector, String model, ConfirmedSlot slot, List<Integer> genreIds) {
		String vectorLiteral = vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
		var parameters = new MapSqlParameterSource().addValue("vector", vectorLiteral).addValue("model", model)
			.addValue("changeType", slot.changeType().name().toLowerCase(java.util.Locale.ROOT))
			.addValue("direction", slot.direction().name().toLowerCase(java.util.Locale.ROOT))
			.addValue("checkDirection", slot.changeType() == com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.ChangeType.MODIFY)
			.addValue("limit", CANDIDATES_PER_SLOT);
		String genreFilter = "";
		if (!genreIds.isEmpty()) {
			genreFilter = "and exists (select 1 from game_tag gt where gt.appid = n.appid and gt.tag_id in (:genres))";
			parameters.addValue("genres", genreIds);
		}
		// OFFSET 0은 자격 확인을 대량 조인으로 풀지 않게 한다. 벡터 인덱스에서 가까운 순서로
		// 읽으면서 변경 종류·방향까지 확인한다. 불일치 청크가 30개 한도를 소모하면
		// 그 뒤의 적합한 사례를 놓치므로 모든 조건을 LIMIT 안에서 검사한다.
		return jdbc.query("""
			with nearest as materialized (
				select c.chunk_id, c.gid, c.embedding <=> cast(:vector as vector) as distance
				from patch_chunk c
				where c.embedding_status = 'succeeded'
				  and c.embedding is not null and c.embedding_model = :model
				  and exists (
					select 1 from patch_change pc
					join patch_change_type ct on ct.change_type_id = pc.change_type_id
					join patch_change_direction d on d.direction_id = pc.direction_id
					where pc.chunk_id = c.chunk_id and ct.code = :changeType
					  and (not :checkDirection or d.code = :direction)
					  and pc.validation_status in ('valid', 'needs_review')
					offset 0
				  )
				  and exists (
					select 1 from news n join patch_stat s on s.gid = n.gid and s.appid = n.appid
					where n.gid = c.gid and n.is_patch = true
					  and s.before_review_count > 0 and s.after_review_count > 0
					  and s.before_positive_pct is not null and s.after_positive_pct is not null
					%s
					offset 0
				  )
				order by c.embedding <=> cast(:vector as vector) limit :limit
			)
			select t.gid, t.chunk_id, 1 - t.distance as cosine from nearest t
			order by cosine desc, t.chunk_id
			""".formatted(genreFilter), parameters,
			(row, index) -> new Match(row.getString("gid"), row.getLong("chunk_id"), row.getDouble("cosine")))
			.stream().filter(match -> Double.isFinite(match.cosine())).toList();
	}

	private List<PatchData> details(List<String> patchIds) {
		return jdbc.query("""
			with timeline as (
				select n.gid, n.published_ts,
				  extract(epoch from (n.published_ts - lag(n.published_ts) over w)) / 86400.0 as interval_days,
				  extract(epoch from (lead(n.published_ts) over w - n.published_ts)) / 86400.0 as next_days,
				  n.appid
				from news n where n.is_patch = true and n.appid in (
				  select appid from news where gid in (:ids))
				window w as (partition by n.appid order by n.published_ts, n.gid)
			), cycles as (
				select gid, next_days, avg(interval_days) over (
				  partition by appid order by published_ts, gid rows between unbounded preceding and current row
				) as average_days from timeline
			)
			select n.gid, n.appid, n.title, n.published_ts, g.name, g.capsule_path,
			       s.before_positive_pct, s.after_positive_pct, s.after_review_count,
			       s.after_positive_pct - s.before_positive_pct as delta_pp,
			       cast(c.average_days as double precision) as average_days,
			       cast(c.next_days as double precision) as next_days
			from news n join game g on g.appid = n.appid
			join patch_stat s on s.gid = n.gid and s.appid = n.appid
			join cycles c on c.gid = n.gid
			where n.gid in (:ids)
			order by n.gid
			""", Map.of("ids", patchIds), (row, index) -> new PatchData(
			row.getString("gid"), row.getLong("appid"), row.getString("name"), row.getString("capsule_path"),
			row.getString("title"), row.getTimestamp("published_ts").toInstant(), row.getLong("after_review_count"),
			row.getBigDecimal("before_positive_pct"), row.getBigDecimal("after_positive_pct"), row.getBigDecimal("delta_pp"),
			row.getObject("average_days", Double.class), row.getObject("next_days", Double.class)));
	}

	private Map<Long, List<Genre>> genresByGame(List<Long> gameIds) {
		Map<Long, List<Genre>> result = new LinkedHashMap<>();
		jdbc.query("""
			select gt.appid, t.tag_id, t.name_ko from game_tag gt join tag t on t.tag_id = gt.tag_id
			where gt.appid in (:ids) order by gt.appid, gt.weight desc, t.tag_id
			""", Map.of("ids", gameIds), row -> {
			result.computeIfAbsent(row.getLong("appid"), key -> new ArrayList<>())
				.add(new Genre(row.getInt("tag_id"), row.getString("name_ko")));
		});
		return result;
	}

	private Map<String, List<StoredChange>> changesByPatch(List<String> patchIds) {
		Map<String, List<StoredChange>> result = new LinkedHashMap<>();
		jdbc.query("""
			select c.gid, c.chunk_id, ct.code as change_type, d.code as direction,
			       coalesce(tt.code, 'unknown') as target_type, coalesce(pc.evidence_quote, '') as quote
			from patch_chunk c join patch_change pc on pc.chunk_id = c.chunk_id
			join patch_change_type ct on ct.change_type_id = pc.change_type_id
			join patch_change_direction d on d.direction_id = pc.direction_id
			left join patch_change_target_type tt on tt.target_type_id = pc.target_type_id
			where c.gid in (:ids) and pc.validation_status in ('valid', 'needs_review')
			order by c.gid, c.seq, pc.patch_change_id
			""", Map.of("ids", patchIds), row -> {
			var change = new CaseChange(row.getString("change_type"), row.getString("direction"),
				row.getString("target_type"), row.getString("quote"));
			result.computeIfAbsent(row.getString("gid"), key -> new ArrayList<>())
				.add(new StoredChange(row.getLong("chunk_id"), change));
		});
		return result;
	}

	public record Genre(int id, String name) {}
	public record GameContext(long gameId, String title, List<Genre> genres) {}
	private record Match(String gid, long chunkId, double cosine) {}
	private record StoredChange(long chunkId, CaseChange change) {}
	public record PatchData(String gid, long gameId, String gameTitle, String capsulePath, String title,
		Instant publishedAt, long reviewCount, BigDecimal beforeRate, BigDecimal afterRate, BigDecimal deltaPp,
		Double averageDays, Double nextDays) {}
	public record Candidate(PatchData patch, double cosine, List<Genre> genres,
		List<CaseChange> changes, boolean changesTruncated) {}
}
