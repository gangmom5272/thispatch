package com.ssafy.thispatch.domain.statistics.repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PlaytimeAnalysisRepository {

	private static final String ASSIGNED_REVIEWS = """
		WITH bands AS (
		    SELECT band_no, playtime_from, playtime_to, review_count AS all_time_count
		    FROM band_stat WHERE appid = :gameId
		), latest_reviews AS (
		    SELECT DISTINCT ON (recommendationid) * FROM recent_review
		    WHERE appid = :gameId
		    ORDER BY recommendationid, updated_ts DESC, review_id DESC
		), assigned_reviews AS (
		    SELECT r.review_id, r.review_text, r.voted_up, r.votes_up, r.playtime_at_review,
		           r.language_code, r.created_ts, r.updated_ts, b.band_no
		    FROM latest_reviews r JOIN bands b
		      ON r.playtime_at_review >= b.playtime_from
		     AND (b.playtime_to IS NULL OR r.playtime_at_review < b.playtime_to)
		    WHERE r.updated_ts >= :startTime AND r.updated_ts < :endTime
		)
		""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public List<BandCount> findBandCounts(long gameId, ReviewPeriod period) {
		return jdbcTemplate.query(ASSIGNED_REVIEWS + """
			SELECT b.band_no, b.playtime_from, b.playtime_to, b.all_time_count,
			       count(r.review_id) AS review_count,
			       count(r.review_id) FILTER (WHERE r.voted_up) AS positive_count
			FROM bands b LEFT JOIN assigned_reviews r ON r.band_no = b.band_no
			GROUP BY b.band_no, b.playtime_from, b.playtime_to, b.all_time_count
			ORDER BY b.band_no
			""", parameters(gameId, period), (row, index) -> new BandCount(row.getInt("band_no"),
				row.getInt("playtime_from"), row.getObject("playtime_to", Integer.class),
				row.getLong("all_time_count"), row.getLong("review_count"), row.getLong("positive_count")));
	}

	public List<TopicCount> findTopicCounts(long gameId, ReviewPeriod period) {
		return jdbcTemplate.query(ASSIGNED_REVIEWS + """
			, mentions AS (
			    SELECT r.band_no, rt.topic_id, count(*) AS mention_count
			    FROM assigned_reviews r JOIN review_topic rt ON rt.review_id = r.review_id
			    GROUP BY r.band_no, rt.topic_id
			)
			SELECT b.band_no, t.topic_id, t.name_ko, coalesce(m.mention_count, 0) AS mention_count
			FROM bands b CROSS JOIN topic t
			LEFT JOIN mentions m ON m.band_no = b.band_no AND m.topic_id = t.topic_id
			ORDER BY t.topic_id, b.band_no
			""", parameters(gameId, period), (row, index) -> new TopicCount(row.getInt("band_no"),
				row.getInt("topic_id"), row.getString("name_ko"), row.getLong("mention_count")));
	}

	public List<BandReview> findFallbackReviews(long gameId, ReviewPeriod period, Integer bandNo) {
		// 선택 대상이 30건 미만일 때만 호출한다. 집계와 동일한 트랜잭션 스냅샷을 사용한다.
		return jdbcTemplate.query(ASSIGNED_REVIEWS + """
			SELECT * FROM assigned_reviews
			WHERE (:bandNo IS NULL OR band_no = :bandNo)
			ORDER BY votes_up DESC, review_id DESC
			""", parameters(gameId, period).addValue("bandNo", bandNo, Types.INTEGER),
			(row, index) -> new BandReview(row.getInt("band_no"), new ReviewRow(row.getLong("review_id"),
				row.getString("review_text"), row.getBoolean("voted_up"), row.getInt("votes_up"),
				row.getObject("playtime_at_review", Integer.class), row.getString("language_code"),
				row.getTimestamp("created_ts").toInstant(), row.getTimestamp("updated_ts").toInstant())));
	}

	private static MapSqlParameterSource parameters(long gameId, ReviewPeriod period) {
		return new MapSqlParameterSource("gameId", gameId)
			.addValue("startTime", Timestamp.from(period.startInclusive()))
			.addValue("endTime", Timestamp.from(period.endExclusive()));
	}

	public record BandCount(int bandNo, int minMinutes, Integer maxMinutesExclusive,
		long allTimeCount, long reviewCount, long positiveCount) {
	}

	public record TopicCount(int bandNo, int topicId, String name, long mentionCount) {
	}

	public record BandReview(int bandNo, ReviewRow review) {
	}
}
