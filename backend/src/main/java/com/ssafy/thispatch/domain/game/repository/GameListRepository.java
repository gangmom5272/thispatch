package com.ssafy.thispatch.domain.game.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.TagItem;
import com.ssafy.thispatch.domain.game.service.GameListCursorCodec.Boundary;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class GameListRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public long count(long memberId, GameListQuery query, GameListScope scope) {
		return jdbc.queryForObject("select count(*) from game g where " + filter(query, scope),
			parameters(memberId, query), Long.class);
	}

	public List<GameRow> findPage(long memberId, GameListQuery query, GameListScope scope, Boundary boundary) {
		var parameters = parameters(memberId, query).addValue("fetchLimit", query.limit() + 1);
		String after = "";
		if (boundary != null) {
			parameters.addValue("lastId", boundary.id());
			if (boundary.value() == null) {
				after = "where sort_value is null and appid > :lastId";
			} else {
				parameters.addValue("lastValue", query.sort().cursorValue(boundary.value()));
				after = "where (sort_value " + query.sort().comparison() + " :lastValue"
					+ " or (sort_value = :lastValue and appid > :lastId) or sort_value is null)";
			}
		}
		// 내 게임은 전체 뉴스의 최신 패치를 계산하지 않고 게임별 기존 인덱스로 조회한다.
		String latestPatches = scope == GameListScope.MY ? "" : """
			latest_patches as (
				select distinct on (n.appid) n.appid, n.gid, n.title
				from news n where n.is_patch = true
				order by n.appid, n.published_ts desc, n.gid desc
			),
			""";
		String latestPatchJoin = scope == GameListScope.MY ? """
			left join lateral (
				select n.gid, n.title from news n
				where n.appid = g.appid and n.is_patch = true
				order by n.published_ts desc, n.gid desc limit 1
			) latest on true
			""" : "left join latest_patches latest on latest.appid = g.appid";
		// 태그·플레이 모드로 행이 늘어나지 않도록 게임 단위 페이지를 먼저 확정한다.
		String sql = """
			with %s candidates as (
				select g.appid, g.name, g.capsule_path, g.developer, g.short_description,
				       (g.release_ts at time zone 'Asia/Seoul')::date as released_on,
				       g.store_review_count, g.store_positive_pct,
				       exists(select 1 from my_game m where m.member_id = :memberId and m.appid = g.appid) as is_mine,
				       latest.title as latest_patch, %s as sort_value
				from game g
				%s
				left join patch_stat ps on ps.gid = latest.gid and ps.appid = g.appid
				where %s
			)
			select * from candidates %s
			order by sort_value %s nulls last, appid asc limit :fetchLimit
			""".formatted(latestPatches, query.sort().expression(), latestPatchJoin,
				filter(query, scope), after, query.sort().direction());
		return jdbc.query(sql, parameters, (rows, rowNum) -> new GameRow(
			rows.getLong("appid"), rows.getString("name"), rows.getString("capsule_path"),
			rows.getString("developer"), rows.getString("short_description"),
			rows.getObject("released_on", LocalDate.class), rows.getObject("store_review_count", Integer.class),
			rows.getObject("store_positive_pct", Integer.class), rows.getBoolean("is_mine"),
			rows.getString("latest_patch"), rows.getString("sort_value")));
	}

	public Map<Long, List<TagItem>> findTags(List<Long> gameIds) {
		Map<Long, List<TagItem>> result = new HashMap<>();
		if (!gameIds.isEmpty()) {
			jdbc.query("""
				select gt.appid, t.tag_id, t.name_ko from game_tag gt
				join tag t on t.tag_id = gt.tag_id
				where gt.appid in (:ids) order by gt.appid, gt.weight desc, t.tag_id asc
				""", new MapSqlParameterSource("ids", gameIds), (org.springframework.jdbc.core.RowCallbackHandler) rows ->
				result.computeIfAbsent(rows.getLong("appid"), ignored -> new ArrayList<>())
					.add(new TagItem(rows.getInt("tag_id"), rows.getString("name_ko"))));
		}
		return result;
	}

	public Map<Long, List<String>> findPlayModes(List<Long> gameIds) {
		Map<Long, List<String>> result = new HashMap<>();
		if (!gameIds.isEmpty()) {
			jdbc.query("""
				select gm.appid, pm.name_ko from game_play_mode gm
				join play_mode pm on pm.play_mode_id = gm.play_mode_id
				where gm.appid in (:ids) order by gm.appid, pm.play_mode_id asc
				""", new MapSqlParameterSource("ids", gameIds), (org.springframework.jdbc.core.RowCallbackHandler) rows ->
				result.computeIfAbsent(rows.getLong("appid"), ignored -> new ArrayList<>()).add(rows.getString("name_ko")));
		}
		return result;
	}

	private String filter(GameListQuery query, GameListScope scope) {
		String filter = "lower(g.name) like :search escape '!'";
		var filters = query.filters();
		if (filters.releaseYearFrom() != null) {
			filter += " and extract(year from g.release_ts at time zone 'Asia/Seoul') >= :releaseYearFrom";
		}
		if (filters.releaseYearTo() != null) {
			filter += " and extract(year from g.release_ts at time zone 'Asia/Seoul') <= :releaseYearTo";
		}
		if (filters.minReviewCount() != null) {
			filter += " and g.store_review_count >= :minReviewCount";
		}
		if (filters.maxReviewCount() != null) {
			filter += " and g.store_review_count <= :maxReviewCount";
		}
		if (filters.minPositiveRate() != null) {
			filter += " and g.store_positive_pct >= :minPositiveRate";
		}
		if (filters.maxPositiveRate() != null) {
			filter += " and g.store_positive_pct <= :maxPositiveRate";
		}
		if (!filters.developer().isEmpty()) {
			filter += " and lower(g.developer) like :developer escape '!'";
		}
		if (!query.genreIds().isEmpty()) {
			filter += " and exists(select 1 from game_tag gt where gt.appid = g.appid and gt.tag_id in (:genreIds))";
		}
		if (scope == GameListScope.MY) {
			filter += " and exists(select 1 from my_game m where m.appid = g.appid and m.member_id = :memberId)";
		}
		return filter;
	}

	private MapSqlParameterSource parameters(long memberId, GameListQuery query) {
		var filters = query.filters();
		return new MapSqlParameterSource("memberId", memberId)
			.addValue("search", query.searchPattern()).addValue("genreIds", query.genreIds())
			.addValue("releaseYearFrom", filters.releaseYearFrom()).addValue("releaseYearTo", filters.releaseYearTo())
			.addValue("minReviewCount", filters.minReviewCount()).addValue("maxReviewCount", filters.maxReviewCount())
			.addValue("minPositiveRate", filters.minPositiveRate()).addValue("maxPositiveRate", filters.maxPositiveRate())
			.addValue("developer", filters.developerPattern());
	}

	public record GameRow(long id, String title, String capsulePath, String developer, String description,
		LocalDate releasedOn, Integer reviewCount, Integer positiveRate, boolean isMine,
		String latestPatch, String sortValue) {
	}
}
