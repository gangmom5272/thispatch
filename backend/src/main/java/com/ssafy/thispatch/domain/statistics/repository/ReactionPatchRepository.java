package com.ssafy.thispatch.domain.statistics.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ReactionPatchRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<Patch> findWithinPeriod(long gameId, ReviewPeriod period) {
		// 순번과 총 개수는 조회 기간을 자르기 전 전체 공지 이력에서 계산한다.
		return jdbcTemplate.query("""
			WITH history AS (
			    SELECT gid, title, published_ts,
			           row_number() OVER (ORDER BY published_ts, gid) AS patch_index,
			           count(*) OVER () AS total_patch_count
			    FROM news WHERE appid = ? AND is_patch = true
			)
			SELECT * FROM history WHERE published_ts >= ? AND published_ts < ?
			ORDER BY patch_index
			""", (row, index) -> new Patch(row.getString("gid"), row.getString("title"),
				row.getTimestamp("published_ts").toInstant().atZone(TimeRule.ZONE).toLocalDate(),
				row.getLong("patch_index"), row.getLong("total_patch_count")),
			gameId, Timestamp.from(period.startInclusive()), Timestamp.from(period.endExclusive()));
	}

	public record Patch(String id, String title, LocalDate patchedOn, long patchIndex, long totalPatchCount) {
	}
}
