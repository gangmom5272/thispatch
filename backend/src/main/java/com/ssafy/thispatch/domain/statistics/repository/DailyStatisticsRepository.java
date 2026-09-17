package com.ssafy.thispatch.domain.statistics.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class DailyStatisticsRepository {

	private final JdbcTemplate jdbcTemplate;

	public LocalDate firstStatDate(long gameId) {
		return jdbcTemplate.queryForObject("SELECT min(stat_date) FROM daily_stat WHERE appid = ?",
			(row, index) -> row.getObject(1, LocalDate.class), gameId);
	}

	public List<DailyCounts> findWithinPeriod(long gameId, ReviewPeriod period) {
		// 서비스 전에 적재를 마치므로 행이 없는 날짜는 리뷰 0건이다.
		// 실제 행의 nullable negative_count는 결측 의미를 보존한다.
		return jdbcTemplate.query("""
			WITH requested_dates AS (
			    SELECT day::date AS stat_date
			    FROM generate_series(CAST(? AS timestamp), CAST(? AS timestamp), INTERVAL '1 day') AS day
			)
			SELECT dates.stat_date, coalesce(stats.review_count, 0) AS review_count,
			       CASE WHEN stats.daily_stat_id IS NULL THEN 0 ELSE stats.negative_count END AS negative_count,
			       coalesce(stats.new_review_count, 0) AS new_review_count,
			       coalesce(stats.new_positive_count, 0) AS new_positive_count,
			       coalesce(stats.edited_review_count, 0) AS edited_review_count,
			       coalesce(stats.edited_positive_count, 0) AS edited_positive_count
			FROM requested_dates dates
			LEFT JOIN daily_stat stats ON stats.stat_date = dates.stat_date AND stats.appid = ?
			ORDER BY dates.stat_date, stats.daily_stat_id
			""", (row, index) -> new DailyCounts(row.getDate("stat_date").toLocalDate(),
				row.getLong("review_count"), row.getObject("negative_count", Integer.class),
				row.getLong("new_review_count"), row.getLong("new_positive_count"),
				row.getLong("edited_review_count"), row.getLong("edited_positive_count")),
			Date.valueOf(period.startDate()), Date.valueOf(period.endDate()), gameId);
	}

	public record DailyCounts(LocalDate date, long reviewCount, Integer negativeCount,
		long firstWrittenCount, long firstWrittenPositiveCount, long updatedCount, long updatedPositiveCount) {
		public long positiveCount() {
			// 이전 적재분의 negative_count가 없으면 두 채널의 긍정 건수로 복원한다.
			return negativeCount == null ? firstWrittenPositiveCount + updatedPositiveCount : reviewCount - negativeCount;
		}
	}
}
