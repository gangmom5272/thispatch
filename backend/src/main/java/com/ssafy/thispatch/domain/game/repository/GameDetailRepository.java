package com.ssafy.thispatch.domain.game.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class GameDetailRepository {

	private final JdbcTemplate jdbc;

	public Optional<GameDetail> findByGameId(long memberId, long gameId) {
		// 한 SQL의 동일한 스냅샷에서 게임, 현재 회원의 등록 여부, 전체 태그를 읽는다.
		return jdbc.query("""
			select g.appid, g.name, g.capsule_path, g.short_description, g.release_ts,
			       g.store_review_count, g.store_positive_pct, g.collected_at,
			       exists(select 1 from my_game m where m.member_id = ? and m.appid = g.appid) as is_mine,
			       t.tag_id, t.name_ko
			from game g
			left join game_tag gt on gt.appid = g.appid
			left join tag t on t.tag_id = gt.tag_id
			where g.appid = ?
			order by gt.weight desc, t.tag_id asc
			""", rows -> {
			if (!rows.next()) {
				return Optional.empty();
			}
			long id = rows.getLong("appid");
			String title = rows.getString("name");
			String capsulePath = rows.getString("capsule_path");
			String description = rows.getString("short_description");
			OffsetDateTime releasedAt = rows.getObject("release_ts", OffsetDateTime.class);
			Integer reviewCount = rows.getObject("store_review_count", Integer.class);
			Integer positiveRate = rows.getObject("store_positive_pct", Integer.class);
			OffsetDateTime collectedAt = rows.getObject("collected_at", OffsetDateTime.class);
			boolean isMine = rows.getBoolean("is_mine");
			List<GameTag> tags = new ArrayList<>();
			do {
				Integer tagId = rows.getObject("tag_id", Integer.class);
				if (tagId != null) {
					tags.add(new GameTag(tagId, rows.getString("name_ko")));
				}
			} while (rows.next());
			return Optional.of(new GameDetail(id, capsulePath, title, description,
				releasedAt == null ? null : releasedAt.toInstant(), reviewCount, positiveRate,
				collectedAt == null ? null : collectedAt.toInstant(), isMine, List.copyOf(tags)));
		}, memberId, gameId);
	}

	public record GameDetail(long id, String capsulePath, String title, String description,
		Instant releasedAt, Integer reviewCount, Integer positiveRate, Instant collectedAt,
		boolean isMine, List<GameTag> tags) {
	}

	public record GameTag(int id, String name) {
	}
}
