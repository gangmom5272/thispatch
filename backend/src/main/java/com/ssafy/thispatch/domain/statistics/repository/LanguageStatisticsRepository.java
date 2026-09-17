package com.ssafy.thispatch.domain.statistics.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class LanguageStatisticsRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public List<LanguageCount> findWithinPeriod(long gameId, ReviewPeriod period) {
		// language_stat은 전체 기간 합계다. 기간별 화면에는 최신 리뷰를 날짜로 걸러 사용한다.
		// 날짜 필터 전에 최신 버전을 골라야 기간 밖으로 수정된 리뷰의 옛 버전을 다시 세지 않는다.
		return jdbcTemplate.query("""
			WITH latest_reviews AS (
			    SELECT DISTINCT ON (recommendationid) language_code, voted_up, updated_ts
			    FROM recent_review
			    WHERE appid = :gameId
			    ORDER BY recommendationid, updated_ts DESC, review_id DESC
			)
			SELECT CASE WHEN r.language_code = 'koreana' THEN 'korean' ELSE r.language_code END AS language_code,
			       max(coalesce(canonical.name_ko, l.name_ko, r.language_code)) AS display_name, count(*) AS review_count,
			       count(*) FILTER (WHERE r.voted_up) AS positive_count
			FROM latest_reviews r LEFT JOIN language l ON l.language_code = r.language_code
			LEFT JOIN language canonical ON canonical.language_code =
			    CASE WHEN r.language_code = 'koreana' THEN 'korean' ELSE r.language_code END
			WHERE r.updated_ts >= :startTime AND r.updated_ts < :endTime
			GROUP BY CASE WHEN r.language_code = 'koreana' THEN 'korean' ELSE r.language_code END
			ORDER BY review_count DESC, language_code
			""", new MapSqlParameterSource("gameId", gameId)
				.addValue("startTime", Timestamp.from(period.startInclusive()))
				.addValue("endTime", Timestamp.from(period.endExclusive())),
			(row, index) -> new LanguageCount(row.getString("language_code"), row.getString("display_name"),
				row.getLong("review_count"), row.getLong("positive_count")));
	}

	public record LanguageCount(String languageCode, String displayName, long reviewCount, long positiveCount) {
	}
}
